node {
    stage("checkout"){
        checkout([$class: 'GitSCM', branches: [[name: '**']], 
        doGenerateSubmoduleConfigurations: false, 
        extensions: [], 
        submoduleCfg: [], 
        userRemoteConfigs: [[refspec: '+refs/heads/develop:refs/remotes/origin/develop +refs/heads/feature-*:refs/remotes/origin/feature-*', url: 'https://github.com/mesmeslip/test-X2.git']]])
        sh '''#! /bin/bash
            echo "test"
            echo $payload
            echo $BRANCH
            git branch
            env
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
