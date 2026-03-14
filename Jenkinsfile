pipeline {
    agent any

    tools {
        jdk   'Java 21'
        maven 'Maven 3.8.1'
    }

    environment {
        JAVA_HOME    = tool 'Java 21'
        M2_HOME      = tool 'Maven 3.8.1'
        SCANNER_HOME = tool 'sonar-scanner'
        PATH = "${JAVA_HOME}/bin:${M2_HOME}/bin:${SCANNER_HOME}/bin:${env.PATH}"
    }

    triggers { githubPush() }

    stages {

        stage('Checkout') {
            steps {
                git branch: 'master',
                    url: 'https://github.com/sofianezineddine/devsecops-app.git',
                    credentialsId: 'git-cred-test'
            }
        }

        stage('Build & Test') {
            steps {
                sh 'mvn clean verify'
            }
            post {
                success {
                    archiveArtifacts artifacts: 'target/*.jar',
                                     fingerprint: true
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withCredentials([string(
                    credentialsId: 'sonar-token',
                    variable: 'SONAR_TOKEN')]) {
                    withSonarQubeEnv('sonar') {
                        sh """
                            sonar-scanner \
                              -Dsonar.projectKey=my-app \
                              -Dsonar.sources=src/main/java \
                              -Dsonar.tests=src/test/java \
                              -Dsonar.java.binaries=target/classes \
                              -Dsonar.login=\$SONAR_TOKEN
                        """
                    }
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

stage('Publish to Nexus') {
    steps {
        sh '''
            cat > /tmp/nexus-settings.xml << EOF
<settings>
  <servers>
    <server>
      <id>nexus-releases</id>
      <username>admin</username>
      <password>Gaj04353@</password>
    </server>
    <server>
      <id>nexus-snapshots</id>
      <username>admin</username>
      <password>Gaj04353@</password>
    </server>
  </servers>
</settings>
EOF
            mvn deploy -DskipTests -s /tmp/nexus-settings.xml
        '''
    }
}

        stage('Build & Push Docker Image') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'docker-cred',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS')]) {
                    script {
                        docker.withRegistry('', 'docker-cred') {
                            def img = docker.build(
                                "${DOCKER_USER}/my-app:${BUILD_NUMBER}")
                            img.push()
                            img.push('latest')
                        }
                    }
                }
            }
        }

        stage('Trivy Image Scan') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'docker-cred',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'IGNORED')]) {
                    sh """
                        trivy image \
                          --format table \
                          -o trivy-report.html \
                          --timeout 30m \
                          --exit-code 0 \
                          "${DOCKER_USER}/my-app:${BUILD_NUMBER}"
                    """
                }
                archiveArtifacts artifacts: 'trivy-report.html',
                                 fingerprint: true
            }
        }

        stage('Render K8s Manifest') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'docker-cred',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'IGNORED')]) {
                    sh '''
                        export IMG_TAG="$DOCKER_USER/my-app:${BUILD_NUMBER}"
                        envsubst < /root/k8s-manifest/deployment.yaml \
                                 > rendered-deployment.yaml
                    '''
                }
                archiveArtifacts artifacts: 'rendered-deployment.yaml',
                                 fingerprint: true
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withKubeCredentials(kubectlCredentials: [[
                    caCertificate: '',
                    clusterName:   'devsecops-cluster',
                    contextName:   '',
                    credentialsId: 'k8s-cred',
                    namespace:     'webapps',
                    serverUrl:     'https://192.168.237.148:6443'
                ]]) {
                    sh 'kubectl apply -f rendered-deployment.yaml'
                    sh 'kubectl rollout status deployment/my-app -n webapps'
                    sh 'kubectl get pods -n webapps'
                }
            }
        }
    }

  post {
        always {
            emailext(
                subject: "Build ${currentBuild.result}: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                body: """
                    <h2>Pipeline: ${currentBuild.result}</h2>
                    <p>Job: ${env.JOB_NAME}</p>
                    <p>Build: #${env.BUILD_NUMBER}</p>
                    <p>URL: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                """,
                to: 'sofianezineddine77@gmail.com',
                mimeType: 'text/html'
            )
        }
    }
}
