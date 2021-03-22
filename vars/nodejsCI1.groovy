def call(body) {
    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

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
                    git clone --single-branch --branch ${SRC_PROJECT_NAME} ${JENKINS_PIPELINES_PARAMS_REPO} .
                    ls"""
                }
            }

            stage('Inject Pipeline Params') {
                steps {
                    load "${pipelineParamsTempDirectory}/${JENKINS_PIPELINES_PARAMS_PATH}"
                }
            }

            stage('TEST Credentials') {
                steps {
                    withCredentials([string(credentialsId: 'domain_name', variable: 'DOMAIN_NAME_TEST_2')]) {
                        sh '''
                        env.DOMAIN_NAME_TEST_22='fortest'
                        echo ${DOMAIN_NAME_TEST_2}
                        '''
                    }
                }
            }

            stage('Print Envs') {
                steps {
                    sh 'printenv'
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
                            sh '''
                            rm -rf node_modules
                            rm -rf ${DEST_PROJECT_NAME}
                            rm -rf dist
                            '''
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
                    if [ ${BRANCH_NAME} = "master" ] || [ ${BRANCH_NAME} = "main" ] || [ ${BRANCH_NAME} = "qa" ]
                    then
                    npm run build:webpack
                    else
                    npm run build:webpack
                    fi'''
                }
            }

            stage('Push to Dest Repo') {
                when {
                    anyOf {
                        branch 'dev';
                        branch 'qa';
                        branch 'main';
                        branch 'master';
                    }
                }
                steps {
                    sh """
                    ls
                    mkdir ${destProjectTempDirectory}
                    cd ${destProjectTempDirectory}
                    #make sure the repository does have the related branch. you might need to manually create all the branches needed for the jenkins like dev, qa.
                    git clone --single-branch --branch ${BRANCH_NAME} https://${DEST_REPO} .
                    rm *
                    cp -a ../dist/. .
                    git config user.name "${GITHUB_CRED_USR}"
                    git config user.email "${GITHUB_USER_EMAIL}"
                    git add .
                    git diff --quiet && git diff --staged --quiet || git commit -am "adding the build files to the dest repo"
                    git push https://${GITHUB_CRED_USR}:${GITHUB_CRED_PSW}@${DEST_REPO}
                    """
                }
            }

            stage('Deployment') {
                when {
                    anyOf {
                        branch 'dev';
                        branch 'qa';
                        branch 'main';
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
                                  domain_name=${DOMAIN_NAME}
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
            RUNDECK_JOB_ID = credentials('nodejs_deployment_v1_id')
            JENKINS_PIPELINES_PARAMS_REPO = credentials('jenkins_pipelines_params_repo')
            JENKINS_PIPELINES_PARAMS_PATH = credentials('jenkins_pipelines_params_path')
            SRC_PROJECT_NAME = """${sh(
                    returnStdout: true,
                    script: '''
                    repo_ref=${GIT_URL##*/}
                    repo_name=${repo_ref%.git}
                    echo ${repo_name}'''
            ).trim()}"""
            DOMAIN_NAME_TEST_22 = ''
        }
    }
}
