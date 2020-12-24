node {
    // CD DEPLOY SCHEDUAL OR NIGHTLY OR IN CI
    //the main branch to check changes , which will have tags , and will deploy to integration environment
    env.MAIN_BRANCH='integration'
    stage("check if changes happened"){
        // checkout only the develop branch
        checkout([$class: 'GitSCM', 
        branches: [[name: "$MAIN_BRANCH"]], 
        doGenerateSubmoduleConfigurations: false, 
        extensions: [], 
        submoduleCfg: [], 
        // userRemoteConfigs: [[refspec: '+refs/heads/develop:refs/remotes/origin/develop +refs/heads/feature-*:refs/remotes/origin/feature-*', url: '${REP_NAME}']]])
        userRemoteConfigs: [[refspec: '+refs/heads/develop:refs/remotes/origin/develop', url: 'https://github.com/mesmeslip/test-X2.git']]])
        sh '''#! /bin/bash
            git fetch
            git checkout $BRANCH_NAME 
            git reset --hard origin/$BRANCH_NAME 
        '''
        // get latest tag name and compare head with tag commit to check if there are changes
        // if there are changes create a release and deploy the services that changed
        env.TAG_STR=sh(script:'git describe --abbrev=0 --tags',returnStdout:true).trim()
        def changes=sh(script:'[ "`git rev-parse ${TAG_STR}`" ==  "`git rev-parse HEAD`" ] && echo y || echo n').trim().toBoolean()
    }
    if (!changes){
        echo 'no changes happened'
        return
    }
    dep_a=sh(script:git-compare-cmd,returnStdout: true)
    dep_srv=dep_a.split("\n")
    ret=[]
    if(ret.size()>0){

    }
    stage("set release notes"){
        // bumping the tag version
        // creating release notes with the changes  
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
            ADDR[2]=$((${ADDR[2]} + 1))
            for i in ${ADDR[@]};do
                nv+=$i
                nv+="."
            done
            tag_ver=$(echo "${nv::-1}")
            nextTag="v${tag_ver}"

            # Find the relevant commits up to the previous tag
            # We search for merged pull requests
            # We can also use the full message if required
            #changes=$(git log $TAG_STR..HEAD --oneline --format=%B | sed '/^$/d' | awk -F' ' '{print $1}')
            changes=$(git log $TAG_STR..HEAD --oneline --format=%B )

            # We can filter out only the ticket number
            changes=$(echo $changes | sed 's/CRM/\\n- CRM/g' | sed '/^$/d')
            RN_F="crm/app/realease_notes.md"
            touch ${RN_F}
            # Append the list of changes to the relase notes file
            echo "$changes" | cat - ${RN_F} > /tmp/out && mv /tmp/out ${RN_F}

            # Append the new version number   
            echo "# $nextTag" | cat - ${RN_F} > /tmp/out && mv /tmp/out ${RN_F}

            head ${RN_F} --lines=20

            # get back to the root folder
            cd -
            git add ${RN_F}

            #save for next stage
            echo "$nextTag" > /tmp/deployTag 
        '''
    }
    stage("make dependencies files"){
        env.nextTag = readFile('/tmp/deployTag ').trim()
        sh '''#!/usr/bin/env bash
            set -e				  
            # Check to see if we have any required modules

            # Set the assoiative array to store the data
            SERVICES_VERSIONS=()

            # read the selected modules
            IFS=',' read -ra MODULES_ARR <<< "${MODULES}"

            # Build the google cli command 
            GCLOUD_CMD="gcloud container images list-tags --quiet --format=json --limit=1"

            # Loop over the modules to fetch
            for MODULE in ${MODULES_ARR[@]}
            do
                # Set the base image to pull the tags of 
                BASE="gcr.io/p3marketers-manage/$MODULE"

                # grab the version ffom GCP Container Registry
                VERSION=`$GCLOUD_CMD $BASE | jq -e ".[0].tags[0]"`
                
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
            git push --follow-tags origin $BRANCH_NAME
        '''
    }
    stage("retag and deploy service: ${SRV} to env: ${ENV_DEPLOY}"){
        env.SERVICE_FOLDER_MF="services_folder_names.json"
        env.SERVICE_DEPLOY_MF="deployments.json"
        sh '''#!/usr/bin/env bash
            GCR_IMAGE_NAME=$( jq -er .\\\"$SRV\\\" <<< $SERVICE_FOLDER_MF)
            echo ">> adding $ENV_DEPLOY tag to the image: $GCR_IMAGE_NAME:$VERSION"

            BASE="gcr.io/p3marketers-manage/$GCR_IMAGE_NAME"
            gcloud container images add-tag --quiet \
            $BASE:$VERSION \
            $BASE:$ENV_DEPLOY

            # map gcr image name to deployment name
            DEPLOY_SERVICE_NAME=$( jq -r .\\\"$SRV\\\" <<< $SERVICE_DEPLOY_MF)
            echo ">> deploying service ${SRV} to cluster, env:$ENV_DEPLOY deployment name: $DEPLOY_SERVICE_NAME"
            
            # deploying service to cluster
            kubectl --kubeconfig=/var/lib/jenkins/.kube/$KUBE_FILE rollout restart deployment $DEPLOY_SERVICE_NAME
        '''
    }
}
