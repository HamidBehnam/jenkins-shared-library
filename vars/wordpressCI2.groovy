/***
 * This is the Static and Simplified version of the wordpressCI1 to cover those projects that don't need the Compile, Build process and Dest Repo.
 * @param body
 * @return
 */
def call(body) {
    // evaluate the body block, and collect configuration into the object
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
        agent {
            docker {
                image 'node'
            }

        }
        stages {
            stage('Pre Build') {
                parallel {
                    stage('Print Info') {
                        steps {
                            sh '''
                            node --version
                            ls'''
                        }
                    }

                    stage('Clearing') {
                        steps {
                            sh '''
                            cd themes/${THEME_NAME}/resources
                            rm -rf node_modules
                            rm -rf dist'''
                        }
                    }
                }
            }

            stage('Deployment') {
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
                                  domain_name=${DOMAIN_NAME}
                                  project_path=${PROJECT_PATH}
                                  deployment_branch=${BRANCH_NAME}
                                  src_repo=${SRC_REPO}
                                  theme_name=${THEME_NAME}
                                  mysql_root_pass=${MYSQL_ROOT_PASS}
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
            DOMAIN_NAME = credentials('domain_name')
            RUNDECK_INSTANCE_NAME = credentials('rundeck_instance_name')
            RUNDECK_JOB_ID = credentials('wordpress_deployment_v2_id')
            MYSQL_ROOT_PASS = credentials('mysql_root_pass')
            SRC_PROJECT_NAME = "${pipelineParams.src_project_name}"
            SRC_REPO = "${pipelineParams.src_repo}"
            PROJECT_PATH = "${pipelineParams.project_path}"
            THEME_NAME = "${pipelineParams.theme_name}"
        }
    }
}