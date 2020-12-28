node{
    // configure default in the job not in the script
    VERSION_TAG=''
    ENV_DEPLOY-'integration'
    srv_map=["crm-app": "shared-crm-app",
    "crm-bpmn": "shared-bpmn-manager",
    "crm-gateway": "brand-integr-crm-gateway",
    "crm-notification": "shared-notifications-service",
    "flowchart-executor": "shared-flowchart-executor",
    "crm-subscriber": "brand-integr-crm-subscriber",
    "crm-mailbox-fetcher": "shared-mailbox-fetcher",
    "commissions-service": "shared-commissions-service",
    "crm-bonus-job": "shared-bonus-job-executor",
    "action-tracking-service": "shared-action-tracking-service"]
    services = srv_map.collect{it.key}
    stage('deploy release to env'){
    //promoting image to env
    //looping on changed services identify between tags
    // if we want to retag all we can iterate on the modules array
    // modules = srv_map.collect{it.value}
    // for(srv in modules){
    for(srv in services){
        env.SRV=srv
        // retag the changed services
        stage("retag and deploy service: ${SRV}"){
            env.SERVICES_JSON="services.json"
            sh '''#!/usr/bin/env bash
                GCR_IMAGE_NAME=${SRV}
                VERSION=$( jq -er .\\\"$SRV\\\" <<< $SERVICES_JSON)
                echo ">> adding $ENV_DEPLOY tag to the image: $GCR_IMAGE_NAME:$VERSION"

                BASE="gcr.io/p3marketers-manage/$GCR_IMAGE_NAME"
                gcloud container images add-tag --quiet \
                $BASE:$VERSION \
                $BASE:$ENV_DEPLOY
            '''
        }
    }
    for(srv in changed_services){
        env.SRV=srv
        env.SRV_DEPLOY=srv_map[srv]
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