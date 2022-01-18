properties([
        parameters([
                string(defaultValue: params.BRANCH_NAME , description: 'Git branch name', name: 'BRANCH_NAME', trim: false),
                string(defaultValue: params.GIT_USER_CREDENTIALS_ID ?: "git-svc-interset-read", description: 'Git user credentials identifier', name: 'GIT_USER_CREDENTIALS_ID', trim: false),
                string(defaultValue: params.REPO_URL ?: "https://github.com/MicroFocus/spark-connector.git", description: ' Git Repo URL', name: 'REPO_URL', trim: false),
                string(defaultValue: params.BUILD_NODE ?: 'build-bot-3', description: 'Node to perform build on', name: 'BUILD_NODE', trim: false),
                string(defaultValue: params.SERVER_ID ?: 'artifactory.ad.interset.com', description: 'interset artifactory server id', name: 'SERVER_ID', trim: false),
            ]),
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '20')),
           disableConcurrentBuilds()
        ])
    

    node("${params.BUILD_NODE}") {
        
        stage('Pull Source') {
        timeout(time: 5, unit: 'MINUTES') {
            git branch: params.BRANCH_NAME ,credentialsId: params.GIT_USER_CREDENTIALS_ID, url: params.REPO_URL
            }
        }

        stage('Build Project and Run tests') {
        timeout(time: 20, unit: 'MINUTES') {
            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                ansiColor('xterm') {
                    sh '''
                        pushd ${WORKSPACE}
                            cd connector
                            sbt package
                            artifact_name="vertica-spark-2.0.3.${BUILD_NUMBER}.jar"
                            mv target/scala-2.12/spark-vertica-connector_2.12-*.jar target/scala-2.12/${artifact_name}
                        popd
                    '''
                }
            }
            
            if(currentBuild.currentResult == "FAILURE") {
                sh 'exit 1'
            }
        }
    }
 
  stage('Publishing jar to artifactory') {
        timeout(time: 20, unit: 'MINUTES') {
             rtUpload (
                    serverId: params.SERVER_ID,
                    spec: """{
                            "files": [
                                    {
                                        "pattern": "${WORKSPACE}/connector/target/scala-2.12/vertica-spark-2.0.3.${BUILD_NUMBER}.jar",
                                        "target": "ext-release-local/com/vertica/spark/vertica-spark/2.0.3/"
                                    }
                                ]
                            }"""
                )
            
        }
    }

    
    stage('Cleanup Workspace') {
            
                cleanWs()
           
        }
  
    }
