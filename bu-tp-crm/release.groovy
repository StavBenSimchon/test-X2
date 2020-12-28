node {
    //testing 
    env.ENV_DEPLOY = 'testing'
    env.MAIN_BRANCH = 'codeWizard-deployment'
    env.DEPLOYMENT_JOB=''
    env.RN_F="crm/app/realease_notes.md"

    // map for retag and deploy
    // env.ENV_DEPLOY = 'qa'
    // env.MAIN_BRANCH = 'integration'
    // env.DEPLOYMENT_JOB='CRM-SERVICES-DEPLOYMENT'

    srv_map=["crm":"crm-app","flowchart-executor":"flowchart-executor","bpmn":"crm-bpmn","gateway-crm":"crm-gateway",
    "notification":"crm-notification","mailbox-fetcher":"crm-mailbox-fetcher","bonus-job":"crm-bonus-job"]
    //convert to list
    env.MODULES = srv_map.collect{it.value}.join(',')
    services = srv_map.collect{it.key}

    stage("check if changes happened"){
        // checkout only the MAIN_BRANCH branch
        checkout([$class: 'GitSCM', 
        branches: [[name: "$MAIN_BRANCH"]], 
        doGenerateSubmoduleConfigurations: false, 
        extensions: [], 
        submoduleCfg: [], 
        userRemoteConfigs: [[
        url:"git@github.com:LMLab/tp-crm.git"]]])
        sh '''#! /bin/bash
            git fetch
            git checkout $MAIN_BRANCH 
            git reset --hard origin/$MAIN_BRANCH
        '''
        // get latest tag name and compare head with tag commit to check if there are changes
        // if there are changes create a release and deploy the services that changed
        // get latest tag
        env.TAG_STR=sh(script:'git describe --abbrev=0 --tags',returnStdout:true).trim()
        // check if were on that same place as the tag, this is a boolean flag
        changed=sh(script:'[ "`git rev-parse ${TAG_STR}`" ==  "`git rev-parse HEAD`" ] && echo n || echo y',returnStdout:true).trim().toBoolean()
        // check git changes between where the head and the tag
        git_compare_cmd='git diff --name-only $TAG_STR..HEAD | grep "/" | cut -f1 -d"/" | uniq'
    }
    if (!changed){
        echo 'no changes happened between tags'
        return
    }
    echo "identifing changes between tags"
    // identify what services have changes by git diffrences by folder
    changed=sh(script:git_compare_cmd,returnStdout: true).split("\n")
    // filter the changed folder to get the changed services
    changed_services=changed.findAll{services.contains(it)}
    // if the services didnt changed skip release
    if(changed_services.size()==0){
        echo 'no changes happened in services, skipping release'
        return
    }
    // release needs to be created
    // creating release notes with the changes  
    stage("set release notes"){
        // placing release notes in folder
        sh '''#!/usr/bin/env bash
            set -e
            # Find the relevant commits up to the previous tag
            # We search for merged pull requests
            # We can also use the full message if required
            #changes=$(git log $TAG_STR..HEAD --oneline --format=%B | sed '/^$/d' | awk -F' ' '{print $1}')
            changes=$(git log $TAG_STR..HEAD --oneline --format=%B )

            # We can filter out only the ticket number
            #changes=$(echo $changes | sed 's/CRM/\\n- CRM/g' | sed '/^$/d')
            
            touch ${RN_F}
            # Append the list of changes to the relase notes file
            echo "$changes" | cat - ${RN_F} > /tmp/out && mv /tmp/out ${RN_F}

            # Append the new version number   
            echo "# $nextTag" | cat - ${RN_F} > /tmp/out && mv /tmp/out ${RN_F}

            head ${RN_F} --lines=20

            git add ${RN_F}            
        '''
    }
    // make services.json file which reflect what will be in the environment and for tracking changes
    stage("make dependencies files"){
        // bumping tag version
        // creating services.json , take the lastest built images from the development 
        // (images with prefix of the main branch sorted by timestamp, grabing the "last" built image)
        sh '''#!/usr/bin/env bash
            set -e	
            # buping the tag version 
            # sanitise the string
            TAG_STR=$(echo $TAG_STR | tr -d "v")
            set +e
            echo "$TAG_STR" | grep -E '^[0-9]+\\.[0-9]+\\.'
            if [ "$?" != 0 ]; then
                echo "the lastest tag is not a number, can't increment if its a string"
                exit 1
            fi
            set -e
            IFS='.' read -ra ADDR <<< "$TAG_STR"
            # which part to bump
            # bumpig the patch
            ADDR[2]=$((${ADDR[2]} + 1))
            for i in ${ADDR[@]};do
                nv+=$i
                nv+="."
            done
            tag_ver=$(echo "${nv::-1}")
            nextTag="v${tag_ver}"
            # Check to see if we have any required modules

            # Set the assoiative array to store the data
            SERVICES_VERSIONS=()

            # read the selected modules
            IFS=',' read -ra MODULES_ARR <<< "${MODULES}"

            # Build the google cli command 
            # filter by mainbranch prefix , take the last created build by timestamp of the build 
            GCLOUD_CMD="gcloud container images list-tags --filter=tags:${MAIN_BRANCH}- --format=json --quiet --limit=1 --format=json"

            # Loop over the modules to fetch
            for MODULE in ${MODULES_ARR[@]}
            do
                # Set the base image to pull the tags of 
                BASE="gcr.io/p3marketers-manage/$MODULE"

                # grab the version from GCP Container Registry
                VERSION=`$GCLOUD_CMD $BASE | jq -er 'select(.[0] != null)[0].tags[] | select (test("${MAIN_BRANCH}"))'`
                # Debug
                echo "Service name: $MODULE, Latest version: $VERSION"
                
                # Store the service name & version in the associative array for later use
                SERVICES_VERSIONS+=("${MODULE}")
                SERVICES_VERSIONS+=("${VERSION}")
            done
            echo ${SERVICES_VERSIONS[@]}
            # Build the JSON output for adding to the repository
            JSON=$({
                echo '{'
                printf '"%s": %s,\n' "${SERVICES_VERSIONS[@]}" | sed '$s/,$//'
                echo '}'
            } | jq .)

            # add the file to the repository
            echo $JSON > services.json

            cat services.json
            git config --local user.email "noreply@jenkins.com"
            git config --local user.name "Jenkins"
            # Commit the changes
            git add services.json         
            git commit -m "bump version to:$nextTag
generated released_notes.md
generated services.json"
            ######### DEBUG #######
            set +e
            git tag -d testing
            git push origin :refs/tags/testing
            set -e
            #######################
            git tag -f -a -m "bump version $nextTag" $nextTag
            git push --follow-tags origin $MAIN_BRANCH
            # create branch for release
            git checkout -b release-$nextTag
            git push 

            # if needed for later
            echo "$nextTag" > /tmp/deployTag 
        '''
    }
    stage("trigger release to env"){
            env.VERSION_TAG=readFile('/tmp/deployTag').trim()
            // build job: "${DEPLOYMENT_JOB}", parameters: [[$class: 'StringParameterValue', name: 'VERSION_TAG', value: "$VERSION_TAG"],[$class: 'StringParameterValue', name: 'ENV_DEPLOY', value: "$ENV_DEPLOY"]]
            build job: "${DEPLOYMENT_JOB}", parameters: [[$class: 'StringParameterValue', name: 'VERSION_TAG', value: "release-$VERSION_TAG"],[$class: 'StringParameterValue', name: 'ENV_DEPLOY', value: "$ENV_DEPLOY"]]
    }
}
