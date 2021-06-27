def call(body) {
    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def agentName = 'angularCI2'
    def pipelineParamsTempDirectory = 'jenkins-pipelines-params'
    def destProjectTempDirectory = 'project-dest'

    pipeline {
        agent {
            docker {
                image 'node'
            }

        }
        stages {
            stage('Clone Pipeline Params Repo') {
                steps {
                    sh """
                    rm -rf ${pipelineParamsTempDirectory}
                    mkdir ${pipelineParamsTempDirectory}
                    cd ${pipelineParamsTempDirectory}
                    git clone --single-branch --branch master ${JENKINS_PIPELINES_PARAMS_REPO} .
                    ls"""
                }
            }

            stage('Inject Pipeline Params') {
                steps {
                    load "${pipelineParamsTempDirectory}/${agentName}/${SRC_PROJECT_NAME}.groovy"
                }
            }

            stage('Pre Build') {
                parallel {
                    stage('Print Info') {
                        steps {
                            sh '''
                            node --version
                            ls
                            '''
                        }
                    }

                    stage('Clearing') {
                        steps {
                            sh """
                            rm -rf node_modules
                            rm -rf ${destProjectTempDirectory}
                            rm -rf dist
                            """
                        }
                    }
                }
            }

            stage('Dependencies Installation') {
                steps {
                    sh 'npm install'
                }
            }

            stage('Build') {
                steps {
                    sh '''
                    if [ ${BRANCH_NAME} = "master" ]
                    then
                    npm run build -- --configuration=production --base-href /${PROJECT_PATH}
                    elif [ ${BRANCH_NAME} = "qa" ]
                    then
                    npm run build -- --configuration=qa --base-href /${PROJECT_PATH}
                    else
                    npm run build -- --configuration=dev --base-href /${PROJECT_PATH}
                    fi'''
                }
            }

            stage('Unit Tests') {
                steps {
                    sh '''
                    apt-get update
                    apt-get install -y libgbm-dev
                    npm run test:ci
                    '''
                }
            }

            stage('Push to Dest Repo') {
                when {
                    anyOf {
                        branch 'dev';
                        branch 'qa';
                        branch 'master';
                    }
                }
                steps {
                    sh """
                    cd dist
                    ls
                    mkdir ${destProjectTempDirectory}
                    cd ${destProjectTempDirectory}
                    #make sure the repository does have the related branch. you might need to manually create all the branches needed for the jenkins like dev, qa.
                    git clone --single-branch --branch ${BRANCH_NAME} https://${DEST_REPO} .
                    rm -rf *
                    cp -a ../${SRC_PROJECT_NAME}/. .
                    git config user.name "${GITHUB_CRED_USR}"
                    git config user.email "${GITHUB_USER_EMAIL}"
                    git add .
                    git diff --quiet && git diff --staged --quiet || git commit -am "adding the build files to the dest repo"
                    git push https://${GITHUB_CRED_USR}:${GITHUB_CRED_PSW}@${DEST_REPO}
                    """
                }
            }

            stage('Deployment') {
                environment {
                    TARGET_DOMAIN_NAME = """${sh(
                            returnStdout: true,
                            script: '''
                            if [ ${DOMAIN_NAME} = 'default' ]; then echo ${DEFAULT_DOMAIN_NAME}; else echo ${DOMAIN_NAME}; fi'''
                    ).trim()}"""
                }
                when {
                    anyOf {
                        branch 'dev';
                        branch 'qa';
                        branch 'master';
                    }
                }
                steps {
                    script {
                        step([$class: "RundeckNotifier",
                              includeRundeckLogs: true,
                              jobId: "${RUNDECK_JOB_ID}",
                              rundeckInstance: "${RUNDECK_INSTANCE_NAME}",
                              options: """
                                  src_project_name=${SRC_PROJECT_NAME}
                                  project_path=${PROJECT_PATH}
                                  deployment_branch=${BRANCH_NAME}
                                  dest_repo=${DEST_REPO}
                                  domain_name=${TARGET_DOMAIN_NAME}
                                  """,
                              shouldFailTheBuild: true,
                              shouldWaitForRundeckJob: true,
                              tailLog: true])
                    }
                }
            }

            stage('Post Build') {
                steps {
                    cleanWs(cleanWhenAborted: true, cleanWhenFailure: true, cleanWhenNotBuilt: true, cleanWhenSuccess: true, cleanWhenUnstable: true, deleteDirs: true, cleanupMatrixParent: true)
                }
            }
        }
        environment {
            HOME = '.'
            GITHUB_CRED = credentials('github_cred')
            GITHUB_USER_EMAIL = credentials('github_user_email')
            RUNDECK_INSTANCE_NAME = credentials('rundeck_instance_name')
            RUNDECK_JOB_ID = credentials('angular_deployment_v1_id')
            JENKINS_PIPELINES_PARAMS_REPO = credentials('jenkins_pipelines_params_repo')
            DEFAULT_DOMAIN_NAME = credentials('default_domain_name')
            SRC_PROJECT_NAME = """${sh(
                    returnStdout: true,
                    script: '''
                    repo_ref=${GIT_URL##*/}
                    repo_name=${repo_ref%.git}
                    echo ${repo_name}'''
            ).trim()}"""
        }
    }
}
