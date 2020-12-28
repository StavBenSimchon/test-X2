node{
    // configure default in the job not in the script
    // env.VERSION_TAG=''
    env.BRANCH_NAME='codeWizard-deployment'
    env.ENV_DEPLOY='testing'
    env.KUBE_FILE="fin-client-devops-test"

    // env.ENV_DEPLOY-'integration'
    // env.KUBE_FILE="crm-tp-$ENV_DEPLOY"
    // env.BRANCH_NAME='integration'
    REP_NAME="tp-crm"
    srv_dep_map=["crm-app": "shared-crm-app",
    "crm-bpmn": "shared-bpmn-manager",
    "crm-gateway": "brand-integr-crm-gateway",
    "crm-notification": "shared-notifications-service",
    "flowchart-executor": "shared-flowchart-executor",
    "crm-subscriber": "brand-integr-crm-subscriber",
    "crm-mailbox-fetcher": "shared-mailbox-fetcher",
    "commissions-service": "shared-commissions-service",
    "crm-bonus-job": "shared-bonus-job-executor",
    "action-tracking-service": "shared-action-tracking-service"]
    services = srv_dep_map.collect{it.key}

    stage("checkout"){
        checkout([$class: 'GitSCM', 
        branches: [[name: "$BRANCH_NAME"]], 
        // branches: [[name: "tags/$BRANCH_NAME"]], 
        doGenerateSubmoduleConfigurations: false, 
        extensions: [], 
        submoduleCfg: [], 
        userRemoteConfigs: [[refspec: "+refs/heads/${BRANCH_NAME}:refs/remotes/origin/${BRANCH_NAME}",
        url: "git@github.com:LMLab/${REP_NAME}.git"]]])
        sh '''#! /bin/bash
            git fetch
            git checkout $BRANCH_NAME 
            git reset --hard origin/$BRANCH_NAME 
        '''
    }
    stage('deploy release to env'){
        //promoting image to env
        //looping on changed services identify between tags
        // if we want to retag all we can iterate on the modules array
        // modules = srv_dep_map.collect{it.value}
        // for(srv in modules){
        for(srv in services){
            env.SRV=srv
            // retag the changed services
            stage("retag and deploy service: ${SRV}"){
                env.SERVICES_JSON="services.json"
                sh '''#!/usr/bin/env bash
                    GCR_IMAGE_NAME=${SRV}
                    VERSION=$( cat $SERVICES_JSON | jq -er .\\\"$SRV\\\")
                    echo ">> adding $ENV_DEPLOY tag to the image: $GCR_IMAGE_NAME:$VERSION"

                    BASE="gcr.io/p3marketers-manage/$GCR_IMAGE_NAME"
                    gcloud container images add-tag --quiet \
                    $BASE:$VERSION \
                    $BASE:$ENV_DEPLOY
                '''
            }
        }
        for(srv in services){
            env.SRV=srv
            env.SRV_DEPLOY=srv_dep_map[srv]
            stage("deploying to env: ${ENV_DEPLOY}"){
                sh '''
                    # map gcr image name to deployment name
                    echo ">> deploying service ${SRV} to cluster, env:$ENV_DEPLOY deployment name: $SRV_DEPLOY"
                    
                    # deploying service to cluster
                    kubectl --kubeconfig=/var/lib/jenkins/.kube/$KUBE_FILE rollout restart deployment $SRV_DEPLOY
                '''
            }
        }
    }
}