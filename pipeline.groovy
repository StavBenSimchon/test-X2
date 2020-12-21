node {
    checkout([$class: 'GitSCM', branches: [[name: '**']], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[refspec: '+refs/heads/master:refs/remotes/origin/master +refs/heads/develop:refs/remotes/origin/develop +refs/heads/release-*:refs/remotes/origin/release-* +refs/heads/feature-*:refs/remotes/origin/feature-*', url: 'https://github.com/mesmeslip/test-X2.git']]])
    echo '''
        echo "test"
    '''
}
