def call(body) {
  // evaluate the body block, and collect configuration into the object
  def pipelineParams= [:]
  if (body) {
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()
  }

  ansiColor('xterm') {
    timeout(10) {
      try {
        cleanWs()

        env.HOME = "${WORKSPACE}"
        dir('chart') {
          def scmVars = checkout scm
          env.GIT_COMMIT = scmVars.GIT_COMMIT
          env.SHORT_COMMIT = env.GIT_COMMIT.take(6)
          env.GIT_BRANCH = scmVars.GIT_BRANCH
          env.BRANCH_NAME = scmVars.GIT_BRANCH
          env.GIT_PREVIOUS_COMMIT = scmVars.GIT_PREVIOUS_COMMIT
          env.GIT_URL = scmVars.GIT_URL
        }

        name = readYaml(file: './chart/Chart.yaml').get('name')
        version = readYaml(file: './chart/Chart.yaml').get('version')
        sh("mv chart ${name}")


        sh("""
          git config --global user.email "jenkins@gavinmogan.com"
          git config --global user.name "Jenkins"
          git config --global push.default simple
        """)

        stage('Download Dependancies') {
          docker.image('alpine/helm:3.11.2').inside('--entrypoint ""') {
            dir(name) {
              sh '''
                ITER=0
                for url in $(grep repository Chart.yaml | awk '{print $2}' | sort | uniq); do
                  helm repo add repo${ITER} ${url}
                  ITER=$(expr $ITER + 1)
                done
                helm dependency build
              '''
            }
          }
        }

        stage('Build Readme') {
          docker.image('jnorwood/helm-docs:v1.5.0').inside('--entrypoint ""') {
            dir(name) {
              sh 'helm-docs'
            }
          }
        }

        stage('Lint') {
          docker.image('alpine/helm:3.11.2').inside('--entrypoint ""') {
            sh "helm lint ${name}"
          }
        }

        if (env.BRANCH_NAME == "master") {
          if (fileExists("${name}/release.config.cjs")) {
            stage('Release') {
              withCredentials([usernamePassword(credentialsId: 'github-halkeye', passwordVariable: 'github_psw', usernameVariable: 'github_usr')]) {
                withEnv([
                    'PREFIX=/tmp/node',
                    'GIT_AUTHOR_NAME=Jenkins',
                    'GIT_AUTHOR_EMAIL=jenkins@gavinmogan.com',
                    'GIT_COMMITTER_NAME=Jenkins',
                    'GIT_COMMITTER_EMAIL=jenkins@gavinmogan.com',
                    "NEW_URL=${env.GIT_URL.replace("https://", "https://${github_usr}:${github_psw}@")}"
                ]) {
                  docker.image('node:18').inside {
                    dir(name) {
                      sh '''
                        npm install semantic-release@20.1.3 @halkeye/helm-semantic-release-config
                        npx semantic-release --repositoryUrl $NEW_URL
                      '''
                    }
                  }
                }
              }
            }
          } else {
            stage('Tag') {
              withCredentials([usernamePassword(credentialsId: 'github-halkeye', passwordVariable: 'github_psw', usernameVariable: 'github_usr')]) {
                dir(name) {
                  sh "git tag -a -m 'v${version}' v${version}"

                  newUrl = env.GIT_URL.replace("https://", "https://${github_usr}:${github_psw}@");
                  sh "git remote add tags ${newUrl}"
                  sh "git push tags v${version}"
                  sh "git remote remove tags"
                }
              }
            }
          }
        }

        stage('Package') {
          docker.image('alpine/helm:3.11.2').inside('--entrypoint ""') {
            sh "helm package ${name}"
          }
          archiveArtifacts("${name}*.tgz")
        }

        lock('helm-charts') {
          stage('Checkout halkeye/helm-charts') {
            withCredentials([usernamePassword(credentialsId: 'github-halkeye', passwordVariable: 'github_psw', usernameVariable: 'github_usr')]) {
              sh 'git clone -b gh-pages https://${github_usr}:${github_psw}@github.com/halkeye/helm-charts.git helm-charts'
            }
          }

          stage('Commit') {
            dir('helm-charts') {
              sh """
                mkdir -p ${name}
                mv ../${name}*.tgz ${name}

                git add ${name}*.tgz
                git commit -m "Adding package"
              """
            }
          }

          if (env.BRANCH_NAME == "master") {
            stage('Deploy') {
              withCredentials([usernamePassword(credentialsId: 'github-halkeye', passwordVariable: 'github_psw', usernameVariable: 'github_usr')]) {
                dir('helm-charts') {
                  sh 'git push origin gh-pages'
                }
              }
            }
          }
        }
      } catch (err) {
        emails = "helm@gavinmogan.com"
        try {
          // TODO map and join
          email  = readYaml(file: './chart/Chart.yaml').get('maintainers')[0].get('email')
        } catch (getEmailsErr) {
        }
        emailext(
          attachLog: true,
          recipientProviders: [developers()],
          body: "Build failed (see ${env.BUILD_URL}): ${err}",
          subject: "[JENKINS] ${env.JOB_NAME} failed",
          to: emails
        )
        throw err
      }
    }
  }
}
