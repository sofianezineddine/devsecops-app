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

       

       stage('OWASP ZAP — DAST Scan') {
    steps {
        script {
            sh 'sleep 15'

            def nodePort = sh(
                script: "kubectl get svc my-app-service -n webapps -o jsonpath='{.spec.ports[0].nodePort}'",
                returnStdout: true
            ).trim()

            def appUrl = "http://192.168.237.148:${nodePort}"
            echo "Scanning: ${appUrl}"

            sh """
                mkdir -p /tmp/zap-reports
                pkill -f "zap.sh" || true
                sleep 3

                zap.sh -daemon \
                    -host 127.0.0.1 \
                    -port 8090 \
                    -config api.disablekey=true \
                    -config api.addrs.addr.name=.* \
                    -config api.addrs.addr.regex=true \
                    -config api.autoupdate=false \
                    -silent &


                echo "Waiting for ZAP daemon..."
                for i in \$(seq 1 60); do
                    curl -s http://127.0.0.1:8090/JSON/core/view/version/ \
                        > /dev/null 2>&1 && echo "ZAP ready!" && break
                    echo "Attempt \$i/40..."
                    sleep 3
                done

                curl -s "http://127.0.0.1:8090/JSON/spider/action/scan/?url=${appUrl}&maxChildren=10"
                sleep 20

                curl -s "http://127.0.0.1:8090/JSON/ascan/action/scan/?url=${appUrl}&recurse=true"
                sleep 60

                curl -s "http://127.0.0.1:8090/OTHER/core/other/htmlreport/" \
                    -o /tmp/zap-reports/zap-report.html

                curl -s "http://127.0.0.1:8090/JSON/core/view/alerts/?baseurl=${appUrl}&start=0&count=200" \
                    -o /tmp/zap-reports/zap-alerts.json

                cp /tmp/zap-reports/zap-report.html zap-report.html
                cp /tmp/zap-reports/zap-alerts.json zap-alerts.json

                curl -s "http://127.0.0.1:8090/JSON/core/action/shutdown/" || true
            """
        }

        archiveArtifacts artifacts: 'zap-report.html, zap-alerts.json',
                         fingerprint: true

        publishHTML(target: [
            allowMissing:          false,
            alwaysLinkToLastBuild: true,
            keepAll:               true,
            reportDir:             '.',
            reportFiles:           'zap-report.html',
            reportName:            'OWASP ZAP Security Report',
            reportTitles:          'DAST Vulnerability Report'
        ])

        sh '''
            echo "=== ZAP Alert Summary ==="
            HIGH=$(grep -o '"risk":"High"' /tmp/zap-reports/zap-alerts.json 2>/dev/null | wc -l || echo 0)
            MED=$(grep -o '"risk":"Medium"' /tmp/zap-reports/zap-alerts.json 2>/dev/null | wc -l || echo 0)
            LOW=$(grep -o '"risk":"Low"' /tmp/zap-reports/zap-alerts.json 2>/dev/null | wc -l || echo 0)
            echo "High:   $HIGH"
            echo "Medium: $MED"
            echo "Low:    $LOW"
            if [ "$HIGH" -gt 0 ]; then
                echo "WARNING: $HIGH HIGH severity vulnerabilities found!"
            fi
        '''
    }
}
    
    }

   post {
    always {
        script {
            // Collect stage results
            def stageResults = []
            def allStages = [
                'Checkout',
                'Build & Test',
                'SonarQube Analysis',
                'Quality Gate',
                'Publish to Nexus',
                'Build & Push Docker Image',
                'Trivy Image Scan',
                'Render K8s Manifest',
                'Deploy to Kubernetes',
                'OWASP ZAP — DAST Scan'
            ]

            // Build stage status table
            def stageRows = ''
            currentBuild.stages?.each { stage ->
                def status = stage.status ?: 'UNKNOWN'
                def color = status == 'SUCCESS' ? '#28a745' :
                            status == 'FAILED'  ? '#dc3545' :
                            status == 'ABORTED' ? '#6c757d' :
                            status == 'SKIPPED' ? '#ffc107' : '#17a2b8'
                def icon  = status == 'SUCCESS' ? '✅' :
                            status == 'FAILED'  ? '❌' :
                            status == 'ABORTED' ? '⛔' :
                            status == 'SKIPPED' ? '⏭️' : 'ℹ️'

                def duration = stage.durationMillis ?
                    "${(stage.durationMillis / 1000).toInteger()}s" : '-'

                def errorMsg = ''
                if (status == 'FAILED' && stage.errorMessage) {
                    errorMsg = "<br><small style='color:#dc3545'>" +
                               stage.errorMessage.take(200) +
                               "</small>"
                }

                stageRows += """
                    <tr>
                        <td style='padding:8px 12px; border-bottom:1px solid #eee;'>
                            ${icon} ${stage.name}
                        </td>
                        <td style='padding:8px 12px; border-bottom:1px solid #eee;
                                   color:${color}; font-weight:bold;'>
                            ${status}
                        </td>
                        <td style='padding:8px 12px; border-bottom:1px solid #eee;
                                   color:#666;'>
                            ${duration}
                        </td>
                        <td style='padding:8px 12px; border-bottom:1px solid #eee;
                                   font-size:12px;'>
                            ${errorMsg}
                        </td>
                    </tr>
                """
            }

            // Overall status color
            def overallColor = currentBuild.result == 'SUCCESS' ? '#28a745' :
                               currentBuild.result == 'FAILURE' ? '#dc3545' :
                               currentBuild.result == 'UNSTABLE'? '#ffc107' : '#6c757d'

            def overallIcon  = currentBuild.result == 'SUCCESS' ? '✅' :
                               currentBuild.result == 'FAILURE' ? '❌' :
                               currentBuild.result == 'UNSTABLE'? '⚠️' : '⛔'

            def totalDuration = currentBuild.durationString ?: '-'

            def emailBody = """
<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"></head>
<body style="font-family:Arial,sans-serif; margin:0; padding:0; background:#f5f5f5;">

  <div style="max-width:700px; margin:20px auto; background:white;
              border-radius:8px; overflow:hidden;
              box-shadow:0 2px 10px rgba(0,0,0,0.1);">

    <!-- Header -->
    <div style="background:${overallColor}; padding:25px; text-align:center;">
      <h1 style="color:white; margin:0; font-size:24px;">
        ${overallIcon} Pipeline ${currentBuild.result ?: 'UNKNOWN'}
      </h1>
    </div>

    <!-- Info Bar -->
    <div style="background:#f8f9fa; padding:15px 20px;
                border-bottom:1px solid #dee2e6;">
      <table style="width:100%; border-collapse:collapse;">
        <tr>
          <td style="padding:4px 0;">
            <strong>📋 Job:</strong> ${env.JOB_NAME}
          </td>
          <td style="padding:4px 0;">
            <strong>🔢 Build:</strong> #${env.BUILD_NUMBER}
          </td>
        </tr>
        <tr>
          <td style="padding:4px 0;">
            <strong>⏱️ Duration:</strong> ${totalDuration}
          </td>
          <td style="padding:4px 0;">
            <strong>🌿 Branch:</strong> master
          </td>
        </tr>
        <tr>
          <td colspan="2" style="padding:4px 0;">
            <strong>🔗 URL:</strong>
            <a href="${env.BUILD_URL}" style="color:#4e9bcd;">
              ${env.BUILD_URL}
            </a>
          </td>
        </tr>
      </table>
    </div>

    <!-- Stage Results Table -->
    <div style="padding:20px;">
      <h2 style="color:#333; margin-top:0; border-bottom:2px solid #dee2e6;
                 padding-bottom:10px;">
        📊 Pipeline Stage Results
      </h2>

      <table style="width:100%; border-collapse:collapse;
                    border-radius:8px; overflow:hidden;
                    box-shadow:0 1px 4px rgba(0,0,0,0.1);">
        <thead>
          <tr style="background:#343a40; color:white;">
            <th style="padding:10px 12px; text-align:left;">Stage</th>
            <th style="padding:10px 12px; text-align:left;">Status</th>
            <th style="padding:10px 12px; text-align:left;">Duration</th>
            <th style="padding:10px 12px; text-align:left;">Details</th>
          </tr>
        </thead>
        <tbody>
          ${stageRows ?: '<tr><td colspan="4" style="padding:10px; color:#666;">No stage data available</td></tr>'}
        </tbody>
      </table>
    </div>

    <!-- Security Reports Links -->
    <div style="padding:0 20px 20px;">
      <h2 style="color:#333; border-bottom:2px solid #dee2e6;
                 padding-bottom:10px;">
        🔒 Security Reports
      </h2>
      <table style="width:100%; border-collapse:collapse;">
        <tr>
          <td style="padding:8px;">
            <a href="${env.BUILD_URL}Trivy_20Security_20Report"
               style="background:#17a2b8; color:white; padding:8px 16px;
                      border-radius:4px; text-decoration:none; display:inline-block;">
              🐳 Trivy Report
            </a>
          </td>
          <td style="padding:8px;">
            <a href="${env.BUILD_URL}OWASP_20ZAP_20Security_20Report"
               style="background:#e74c3c; color:white; padding:8px 16px;
                      border-radius:4px; text-decoration:none; display:inline-block;">
              🕷️ ZAP Report
            </a>
          </td>
          <td style="padding:8px;">
            <a href="http://192.168.237.148:9000/dashboard?id=my-app"
               style="background:#4e9bcd; color:white; padding:8px 16px;
                      border-radius:4px; text-decoration:none; display:inline-block;">
              📊 SonarQube
            </a>
          </td>
          <td style="padding:8px;">
            <a href="http://192.168.237.148:3000/d/falco-runtime"
               style="background:#e74c3c; color:white; padding:8px 16px;
                      border-radius:4px; text-decoration:none; display:inline-block;">
              🛡️ Falco Dashboard
            </a>
          </td>
        </tr>
      </table>
    </div>

    <!-- Footer -->
    <div style="background:#343a40; color:#adb5bd; padding:15px;
                text-align:center; font-size:12px;">
      DevSecOps Pipeline — Jenkins CI/CD |
      Generated: ${new Date().format('yyyy-MM-dd HH:mm:ss')}
    </div>

  </div>
</body>
</html>
            """

            emailext(
                subject: "${overallIcon} [${currentBuild.result ?: 'UNKNOWN'}] ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                body: emailBody,
                to: 'sofianezineddine77@gmail.com',
                mimeType: 'text/html'
            )
        }
    }
}
}