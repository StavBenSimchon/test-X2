node {
    // manual configuration
    // testing
    env.MAIN_BRANCH="develop"
    env.ENV_DEPLOY="testing"
    env.KUBE_FILE="fin-client-devops-test"

    env.ACTION = ''
    env.REP_NAME = ''
    env.MERGED = true
    env.BRANCH = "$BRANCH"
    env.MERGED_TO = "$MAIN_BRANCH"
    // env.MAIN_BRANCH="integration"
    // env.ENV_DEPLOY="integration"
    // env.KUBE_FILE="crm-tp-$ENV_DEPLOY"
    srv_map=["crm":"crm-app","flowchart-executor":"flowchart-executor","bpmn":"crm-bpmn","gateway-crm":"crm-gateway",
    "notification":"crm-notification","mailbox-fetcher":"crm-mailbox-fetcher","bonus-job":"crm-bonus-job"]
    env.MODULES = srv_map.collect{it.value}.join(',')
    services = srv_map.collect{it.key}
    regex = "^(${MAIN_BRANCH}|feature-)"
    // git_compare_cmd is the command to tell us which services have changed, so a build will accure on them only
    // compare differences between pull request branch with main branch
    git_compare_cmd="git diff --name-only origin/${MAIN_BRANCH}..HEAD | grep \"/\" | cut -f1 -d\"/\" | uniq"

    //auto configuration
    if (payload){
        stage('parse payload'){
            env.ACTION = sh (
                script: 'set +x; echo $payload | jq -e  .action | tr -d \\\"',
                returnStdout: true
            ).trim()   
            env.REP_NAME = sh (
                script: 'set +x; echo $payload | jq -e  .repository.name | tr -d \\\"',
                returnStdout: true
            ).trim()
            env.BRANCH = sh (
                script: 'set +x; echo $payload | jq -e  .pull_request.head.ref | tr -d \\\"',
                returnStdout: true
            ).trim() 
            env.MERGED = sh (
                script: 'set +x; echo $payload | jq -e  .pull_request.merged | tr -d \\\"',
                returnStdout: true
            ).trim()
            env.MERGED_TO = sh (
                script: 'set +x; echo $payload | jq -e  .pull_request.base.ref | tr -d \\\"',
                returnStdout: true
            ).trim()
        }
    switch(env.ACTION){
        case ["opened" , "reopened"]:
            break
        case "closed":
                //  check if its merged and not closing pull request
                //  exiting if its closing pull request
            if (env.MERGED == "false"){
                    echo "ignoring trigger, closed pull request"
                    return
            }
            break
            default:
                    echo "ignore trigger: $ACTION"
                return
                break
        }
    }
    env.BRANCH_NAME="$BRANCH"
                if (env.MERGED=="true"){
                    // for merged pull request we will checkout the branch we merged to
                    env.BRANCH_NAME="$MERGED_TO"
                    // because we merged we are on top of the "main branch", 
                    // identify head commit to the last merged commit , after merge its including all the merged commits
                    git_compare_cmd='git diff --name-only $(git log --pretty=format:"%H" --merges -n 2 | tail -n 1)..HEAD | grep "/" | cut -f1 -d"/" | uniq'
                    //if we want to identify pull request merge to ${MAIN_BRANCH}
                    // if (env.MERGED_TO == env.MAIN_BRANCH){
                    //         env.BRANCH_NAME="$MAIN_BRANCH"
                    // }
                }
    // if the head branch or the base branch is not the gitflow branches exit the job
    if(!(BRANCH_NAME.matches(regex) || MERGED_TO.matches(regex))){
        echo "branch:$BRANCH_NAME is not good"
            return
    }
    // if the pull request is open/reopen cotinue to uild
    // if the pull request is closed check if its merged or closed pull request
    // if its merged we will change the branch we pull to the merged branch 
    // example: if branch a merged to b we will build branch b
    stage("clean workspace"){
        cleanWs()
    }
    // CI BUILD ON changes
    stage("checkout"){
        checkout([$class: 'GitSCM', 
        branches: [[name: "$BRANCH_NAME"]], 
        doGenerateSubmoduleConfigurations: false, 
        extensions: [], 
        submoduleCfg: [], 
        // userRemoteConfigs: [[refspec: '+refs/heads/${MAIN_BRANCH}:refs/remotes/origin/${MAIN_BRANCH} +refs/heads/feature-*:refs/remotes/origin/feature-*', url: '${REP_NAME}']]])
        userRemoteConfigs: [[refspec: "+refs/heads/${MAIN_BRANCH}:refs/remotes/origin/${MAIN_BRANCH} +refs/heads/feature-*:refs/remotes/origin/feature-*", url: 'https://github.com/mesmeslip/test-X2.git']]])
        sh '''#! /bin/bash
            git fetch
            git checkout $BRANCH_NAME 
            git reset --hard origin/$BRANCH_NAME 
        '''
    }
    echo "using git command to identify changes: $git_compare_cmd "
    // identify what services have changes by git diffrences by folder
    changed=sh(script:git_compare_cmd,returnStdout: true).split("\n")
    // filter the changed folder to get the changed services
    changed_services=changed.findAll{services.contains(it)}
    // check if there are services that changed
    if(changed_services.size()>0){
        if(BRANCH_NAME==MAIN_BRANCH){
        // iterate the services and bump the version in package.json
            for(srv in changed_services){
                env.SRV=srv
                stage("bump ${SRV} package.json version"){
                    sh '''
                    #bumping version in package.json
                    cd ${SRV}
                    npm version patch --no-git-tag-version
                    cd ..
                    git add ${SRV}/package.json 
                    '''
                    }
            }
            // convert to string for the commit description
            changed_services_str=changed_services.join(' ')
            stage("commit changes"){
                sh '''
                # setting user for commit
                git config --local user.email "no-response@jenkins.com"
                git config --local user.name  "Jenkins"
                git commit -m "Version bump for services: $changed_services_str"
                git push origin
                '''
            }
        }
        // iterate services and build image for each one
        // docker image format: branchName_version_buildNumber
        for(srv in changed_services){
            env.SRV=srv
            stage("get version ${SRV} for build"){
                env.T_VERSION = sh (
                    script: "cat ${SRV}/package.json | jq -r .version",
                    returnStdout: true
                ).trim()
                env.VERSION = "${BRANCH_NAME}_${T_VERSION}_${currentBuild.number}"
            }
            stage("building ${SRV} image for GCR"){
                echo "Building ${SRV} docker image version: ${VERSION}"
                // googleCloudBuild credentialsId: 'p3marketers-manage', request: file("${SRV}/cloudbuild.yaml"), source: local("${SRV}"), substitutions: [_BUILD_TAG: "$VERSION"]
            }
        }
    }
    else{
        stage("dependencies skipped"){
            echo "no changes detected"
        }
    }
}
