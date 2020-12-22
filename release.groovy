node {
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
        env.COMMIT_SHA = sh (
            script: 'set +x; echo $payload | jq -e  .pull_request.head.sha | tr -d \\\"',
            returnStdout: true
        ).trim()
        // env.MAIN_BRANCH="integration"
        // env.ENV_DEPLOY="integration"
        env.EXIT_AFTER_TEST=true
        env.BRANCH_NAME="$BRANCH"
        env.NEED_BUILD=false
        BUILD_DEP=false
        // testing
        env.MAIN_BRANCH="CodeWizard-deployment"
        services=["crm","flowchart-executor","bpmn","gateway-crm","notification","mailbox-fetcher","bonus-job"]
        env.MODULES='crm-app,crm-gateway,crm-notification,crm-bpmn,flowchart-executor,crm-bonus-job,crm-mailbox-fetcher'
        
        env.COMIT=false 
        // deploy var
        // env.KUBE_FILE="crm-tp-$ENV_DEPLOY"
        env.ENV_DEPLOY="testing"
        env.KUBE_FILE="fin-client-devops-test"
        }
        if ( ! MERGED=="true" ){
            return
        }
        stage("clean workspace"){
            cleanWs()
        }
        dep_a=sh(script:'git diff --name-only $(git log --pretty=format:"%H" --merges -n 2 | tail -n 1)..HEAD | grep "/" | cut -f1 -d"/" | uniq',returnStdout: true)
        dep_srv=dep_a.split("\n")
        ret=[]
        if(ret.size()>0){
                for(srv in ret){
                    env.SRV=srv
                    stage("bump ${SRV} package.json version"){
                        sh '''
                        #bumping version in package.json
                        cd ${SRV}
                        npm version patch --no-git-tag-version
                        cd ..
                        '''
                        }
                }
                stage("commit changes"){
                    sh '''
                    # Commit the desired changes to git
                    git config --local user.email "no-response@jenkins.com"
                    git config --local user.name  "Jenkins"

                    git add ${SRV}/package.json ${RN_F}
                    git commit -m "Version bump for services"
                    git push origin
                    '''
                }
                for(srv in ret){
                    env.SRV=srv
                    stage("get version ${SRV} for build"){
                        env.T_VERSION = sh (
                            script: "cat ${SRV}/package.json | jq -r .version",
                            returnStdout: true
                        ).trim()
                        env.VERSION = "${T_VERSION}_${currentBuild.number}"
                    }
                    stage("building ${SRV} image for GCR"){
                        echo "Building ${SRV} docker image version: ${VERSION}"
                        // googleCloudBuild credentialsId: 'p3marketers-manage', request: file("${SRV}/cloudbuild.yaml"), source: local("${SRV}"), substitutions: [_BUILD_TAG: "$VERSION"]
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
            }
            else{
                stage("dependencies skipped"){
                    echo "skipped"
                }
            }
    stage("checkout"){
        checkout([$class: 'GitSCM', branches: [[name: "$branch"]], 
        doGenerateSubmoduleConfigurations: false, 
        extensions: [], 
        submoduleCfg: [], 
        userRemoteConfigs: [[refspec: '+refs/heads/develop:refs/remotes/origin/develop ', url: 'https://github.com/mesmeslip/test-X2.git']]])
        sh '''#! /bin/bash
            echo "test"
            echo $payload
            echo $BRANCH
            git branch
            env
        '''
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
