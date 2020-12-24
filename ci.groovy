node {
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
    }
    else
    {
        env.ACTION =  ''
        env.REP_NAME = ${REPO}
        env.BRANCH = ${BRANCH}
        env.MERGED = false
        env.MERGED_TO = ''
    }
    // env.MAIN_BRANCH="integration"
    // env.ENV_DEPLOY="integration"
    // env.KUBE_FILE="crm-tp-$ENV_DEPLOY"
    services=["crm","flowchart-executor","bpmn","gateway-crm","notification","mailbox-fetcher","bonus-job"]
    env.MODULES='crm-app,crm-gateway,crm-notification,crm-bpmn,flowchart-executor,crm-bonus-job,crm-mailbox-fetcher'
    env.BRANCH_NAME="$BRANCH"
    BUILD_DEP=false
    regex = '^(develop|feature-)'
    // testing
    env.MAIN_BRANCH="main"
    env.ENV_DEPLOY="testing"
    env.KUBE_FILE="fin-client-devops-test"
    // git-compare-cmd is the command to tell us which services have changed, so a build will accure on them only
    // compare differences between pull request branch with main branch
    git-compare-cmd='git diff --name-only origin/${MAIN_BRANCH}..HEAD | grep "/" | cut -f1 -d"/" | uniq'

    // if the head branch or the base branch is not the gitflow branches exit the job
    if(BRANCH.matches(regex) || MERGED_TO.matches(regex)){
        return
    }
    // if the pull request is open/reopen cotinue to uild
    // if the pull request is closed check if its merged or closed pull request
    // if its merged we will change the branch we pull to the merged branch 
    // example: if branch a merged to b we will build branch b
    switch(env.ACTION){
        case ["opened" , "reopened"]:
            // identify changes between the head to the "main branch"
            
            break
        case "closed":
        //  check if its merged and not closing pull request
        //  exiting if its closing pull request
                if (env.MERGED=="true"){
                    // for merged pull request we will checkout the branch we merged to
                    env.BRANCH_NAME="$MERGED_TO"
                    // because we merged we are on top of the "main branch", 
                    // identify head commit to the last merged commit , after merge its including all the merged commits
                    git-compare-cmd='git diff --name-only $(git log --pretty=format:"%H" --merges -n 2 | tail -n 1)..HEAD | grep "/" | cut -f1 -d"/" | uniq'
                    //if we want to identify pull request merge to develop
                    // if (env.MERGED_TO == env.MAIN_BRANCH){
                    //         env.BRANCH_NAME="$MAIN_BRANCH"
                    // }
                }else{
                    echo "ignoring trigger, closed pull request"
                    return
                }
            break
        default:
                echo "ignore trigger: $ACTION"
            return
            break
    }
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
        // userRemoteConfigs: [[refspec: '+refs/heads/develop:refs/remotes/origin/develop +refs/heads/feature-*:refs/remotes/origin/feature-*', url: '${REP_NAME}']]])
        userRemoteConfigs: [[refspec: '+refs/heads/develop:refs/remotes/origin/develop +refs/heads/feature-*:refs/remotes/origin/feature-*', url: 'https://github.com/mesmeslip/test-X2.git']]])
        sh '''#! /bin/bash
            git fetch
            git checkout $BRANCH_NAME 
            git reset --hard origin/$BRANCH_NAME 
        '''
    }
    // identify what services have changes by git diffrences by folder
    changed=sh(script:git-compare-cmd,returnStdout: true).split("\n")
    // filter the changed folder to get the changed services
    changed_services=changed.findAll{services.contains(it)}
    // check if there are services that changed
    if(changed_services.size()>0){
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
            // CD DEPLOY SCHEDUAL OR NIGHTLY OR IN CI
            // stage("retag and deploy service: ${SRV} to env: ${ENV_DEPLOY}"){
            //     env.SERVICE_FOLDER_MF="services_folder_names.json"
            //     env.SERVICE_DEPLOY_MF="deployments.json"
            //     sh '''#!/usr/bin/env bash
            //         GCR_IMAGE_NAME=$( jq -er .\\\"$SRV\\\" <<< $SERVICE_FOLDER_MF)
            //         echo ">> adding $ENV_DEPLOY tag to the image: $GCR_IMAGE_NAME:$VERSION"

            //         BASE="gcr.io/p3marketers-manage/$GCR_IMAGE_NAME"
            //         gcloud container images add-tag --quiet \
            //         $BASE:$VERSION \
            //         $BASE:$ENV_DEPLOY

            //         # map gcr image name to deployment name
            //         DEPLOY_SERVICE_NAME=$( jq -r .\\\"$SRV\\\" <<< $SERVICE_DEPLOY_MF)
            //         echo ">> deploying service ${SRV} to cluster, env:$ENV_DEPLOY deployment name: $DEPLOY_SERVICE_NAME"
                    
            //         # deploying service to cluster
            //         kubectl --kubeconfig=/var/lib/jenkins/.kube/$KUBE_FILE rollout restart deployment $DEPLOY_SERVICE_NAME
            //     '''
            // }
        }
    }
    else{
        stage("dependencies skipped"){
            echo "skipped"
        }
    }
    stage("parse"){
        sh '''
            echo $payload | jq .
        '''
    }
    stage("build"){
        echo "build"
    }
    stage("test"){
        echo "test"
    }
    stage("deploy"){
        echo "deploy"
    }
}
