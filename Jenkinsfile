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
        PATH         = "${JAVA_HOME}/bin:${M2_HOME}/bin:${SCANNER_HOME}/bin:${env.PATH}"
        DOCKER_USER  = 'sofiane235'
        IMAGE_NAME   = "${DOCKER_USER}/my-app"
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
                withSonarQubeEnv('sonar') {
                    sh """
                        sonar-scanner \
                          -Dsonar.projectKey=my-app \
                          -Dsonar.sources=src/main/java \
                          -Dsonar.tests=src/test/java \
                          -Dsonar.java.binaries=target/classes
                    """
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
                withCredentials([usernamePassword(
                    credentialsId: 'nexus-cred',
                    usernameVariable: 'NEXUS_USER',
                    passwordVariable: 'NEXUS_PASS')]) {
                    sh '''
                        cat > /tmp/nexus-settings.xml << EOF
<settings>
  <servers>
    <server>
      <id>nexus-releases</id>
      <username>${NEXUS_USER}</username>
      <password>${NEXUS_PASS}</password>
    </server>
    <server>
      <id>nexus-snapshots</id>
      <username>${NEXUS_USER}</username>
      <password>${NEXUS_PASS}</password>
    </server>
  </servers>
</settings>
EOF
                        mvn deploy -DskipTests -s /tmp/nexus-settings.xml
                    '''
                }
            }
        }

        stage('Build & Push Docker Image') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'docker-cred',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS')]) {
                    sh """
                        # Login to Docker Hub
                        echo "\$DOCKER_PASS" | docker login \
                            -u "\$DOCKER_USER" --password-stdin

                        # Setup buildx builder if not exists
                        docker buildx inspect jenkins-builder > /dev/null 2>&1 \
                            || docker buildx create \
                                --name jenkins-builder \
                                --driver docker-container \
                                --bootstrap

                        # Use the builder
                        docker buildx use jenkins-builder

                        # Build and push in one command using BuildKit
                        docker buildx build \
                            --platform linux/amd64 \
                            --tag \${DOCKER_USER}/my-app:${BUILD_NUMBER} \
                            --tag \${DOCKER_USER}/my-app:latest \
                            --cache-from type=registry,ref=\${DOCKER_USER}/my-app:cache \
                            --cache-to   type=registry,ref=\${DOCKER_USER}/my-app:cache,mode=max \
                            --push \
                            .

                        # Logout
                        docker logout
                    """
                }
            }
        }

        stage('Trivy Image Scan') {
    steps {
        sh """
            # Download Java DB only if not cached yet
            if [ ! -d "\$HOME/.cache/trivy/java-db" ]; then
                echo "Java DB not found, downloading..."
                TRIVY_JAVA_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-java-db:1 \
                trivy image --download-java-db-only
            fi

            # Download official HTML template if not exists
            if [ ! -f /tmp/trivy-html.tpl ]; then
                curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/html.tpl \
                  -o /tmp/trivy-html.tpl
            fi

            # Generate beautiful HTML report using template
            TRIVY_JAVA_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-java-db:1 \
            trivy image \
              --format template \
              --template "@/tmp/trivy-html.tpl" \
              -o trivy-report.html \
              --timeout 15m \
              --exit-code 0 \
              --scanners vuln \
              --skip-db-update \
              --skip-java-db-update \
              ${IMAGE_NAME}:${BUILD_NUMBER}

            echo "Report generated successfully"
        """
        archiveArtifacts artifacts: 'trivy-report.html',
                         fingerprint: true

        // publish HTML report directly in Jenkins UI
        publishHTML(target: [
            allowMissing:          false,
            alwaysLinkToLastBuild: true,
            keepAll:               true,
            reportDir:             '.',
            reportFiles:           'trivy-report.html',
            reportName:            'Trivy Security Report',
            reportTitles:          'Trivy Vulnerability Report'
        ])
    }
}

        stage('Render K8s Manifest') {
            steps {
                sh """
                    export IMG_TAG="${IMAGE_NAME}:${BUILD_NUMBER}"
                    envsubst < /root/k8s-manifest/deployment.yaml \
                             > rendered-deployment.yaml
                    cat rendered-deployment.yaml
                """
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
    stage('DAST — OWASP ZAP') {
    steps {
        sh """
            echo "Waiting 15 seconds for app to stabilize..."
            sleep 15

            for i in 1 2 3 4 5; do
                if curl -sf http://192.168.237.148:30080 -o /dev/null; then
                    echo "App is UP"
                    break
                fi
                echo "Attempt \$i failed — retrying..."
                sleep 5
            done

            mkdir -p ${WORKSPACE}/zap-reports

            # Run ZAP — NO volume mount, reports stay inside container
            docker run --name zap-${BUILD_NUMBER} \\
              --network devsecops \\
              -u root \\
              ghcr.io/zaproxy/zaproxy:stable \\
              zap-baseline.py \\
                -t http://192.168.237.148:30080 \\
                -r /tmp/zap-report.html \\
                -J /tmp/zap-report.json \\
                -l WARN \\
                -I || true

            # Copy reports from container to Jenkins workspace
            docker cp zap-${BUILD_NUMBER}:/tmp/zap-report.html ${WORKSPACE}/zap-reports/
            docker cp zap-${BUILD_NUMBER}:/tmp/zap-report.json ${WORKSPACE}/zap-reports/

            # Clean up container
            docker rm zap-${BUILD_NUMBER}

            echo "Reports generated:"
            ls -la ${WORKSPACE}/zap-reports/
        """

        publishHTML(target: [
            allowMissing:          false,
            alwaysLinkToLastBuild: true,
            keepAll:               true,
            reportDir:             "${WORKSPACE}/zap-reports",
            reportFiles:           'zap-report.html',
            reportName:            'OWASP ZAP DAST Report',
            reportTitles:          'ZAP Vulnerability Report'
        ])

        archiveArtifacts(
            artifacts:         'zap-reports/zap-report.*',
            fingerprint:       true,
            allowEmptyArchive: false
        )
    }
    post {
        always {
            sh """
                echo "==============================="
                echo "=== ZAP SCAN SUMMARY ==="
                echo "==============================="
                if [ -f ${WORKSPACE}/zap-reports/zap-report.json ]; then
                    python3 -c "
import json
with open('${WORKSPACE}/zap-reports/zap-report.json') as f:
    d = json.load(f)
total = {'High':0,'Medium':0,'Low':0,'Informational':0}
for site in d.get('site',[]):
    for a in site.get('alerts',[]):
        r = a.get('riskdesc','').split(' ')[0]
        if r in total: total[r]+=1
print('  HIGH   :', total['High'])
print('  MEDIUM :', total['Medium'])
print('  LOW    :', total['Low'])
print('  INFO   :', total['Informational'])
"
                else
                    echo "  No report found"
                fi
                echo "==============================="
            """
            // always clean up container if it still exists
            sh "docker rm zap-${BUILD_NUMBER} 2>/dev/null || true"
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