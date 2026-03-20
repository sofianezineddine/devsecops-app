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
        stage('SonarQube Report') {
    steps {
        withCredentials([string(
            credentialsId: 'sonar-token',
            variable: 'SONAR_TOKEN')]) {
            sh '''
                echo "Fetching SonarQube report..."

                SONAR_URL="http://sonarqube:9000"
                PROJECT="my-app"

                # Fetch project metrics
                curl -s -u "${SONAR_TOKEN}:" \
                  "${SONAR_URL}/api/measures/component?component=${PROJECT}&metricKeys=bugs,vulnerabilities,code_smells,security_hotspots,coverage,duplicated_lines_density,ncloc,alert_status" \
                  -o /tmp/sonar-metrics.json

                # Fetch vulnerabilities
                curl -s -u "${SONAR_TOKEN}:" \
                  "${SONAR_URL}/api/issues/search?componentKeys=${PROJECT}&types=VULNERABILITY&severities=CRITICAL,MAJOR&ps=50" \
                  -o /tmp/sonar-vulns.json

                # Fetch bugs
                curl -s -u "${SONAR_TOKEN}:" \
                  "${SONAR_URL}/api/issues/search?componentKeys=${PROJECT}&types=BUG&severities=CRITICAL,MAJOR&ps=50" \
                  -o /tmp/sonar-bugs.json

                # Fetch security hotspots
                curl -s -u "${SONAR_TOKEN}:" \
                  "${SONAR_URL}/api/hotspots/search?projectKey=${PROJECT}&ps=50" \
                  -o /tmp/sonar-hotspots.json

                echo "Data fetched, generating report..."

                # Parse metrics using shell
                BUGS=$(grep -o '"metric":"bugs","value":"[^"]*"' /tmp/sonar-metrics.json | grep -o '"value":"[^"]*"' | tr -d '"value:' | tr -d '"' || echo 0)
                VULNS=$(grep -o '"metric":"vulnerabilities","value":"[^"]*"' /tmp/sonar-metrics.json | grep -o '"value":"[^"]*"' | tr -d '"value:' | tr -d '"' || echo 0)
                SMELLS=$(grep -o '"metric":"code_smells","value":"[^"]*"' /tmp/sonar-metrics.json | grep -o '"value":"[^"]*"' | tr -d '"value:' | tr -d '"' || echo 0)
                HOTSPOTS=$(grep -o '"metric":"security_hotspots","value":"[^"]*"' /tmp/sonar-metrics.json | grep -o '"value":"[^"]*"' | tr -d '"value:' | tr -d '"' || echo 0)
                STATUS=$(grep -o '"metric":"alert_status","value":"[^"]*"' /tmp/sonar-metrics.json | grep -o '"value":"[^"]*"' | tr -d '"value:' | tr -d '"' || echo UNKNOWN)
                NCLOC=$(grep -o '"metric":"ncloc","value":"[^"]*"' /tmp/sonar-metrics.json | grep -o '"value":"[^"]*"' | tr -d '"value:' | tr -d '"' || echo 0)

                STATUS_COLOR=$([ "$STATUS" = "OK" ] && echo "#28a745" || echo "#dc3545")
                STATUS_ICON=$([ "$STATUS" = "OK" ] && echo "✅ PASSED" || echo "❌ FAILED")

                # Generate HTML report
                cat > sonarqube-report.html << HTMLEOF
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>SonarQube Report — my-app</title>
<style>
  body { font-family: Arial, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; }
  h1 { color: #333; border-bottom: 3px solid #4e9bcd; padding-bottom: 10px; }
  .summary { display: flex; flex-wrap: wrap; gap: 15px; margin: 20px 0; }
  .card {
    background: white; border-radius: 8px; padding: 20px;
    min-width: 140px; text-align: center;
    box-shadow: 0 2px 6px rgba(0,0,0,0.1);
  }
  .card .number { font-size: 42px; font-weight: bold; }
  .card .label { font-size: 13px; color: #666; margin-top: 5px; }
  .bugs .number     { color: #dc3545; }
  .vulns .number    { color: #ff6b35; }
  .smells .number   { color: #ffc107; }
  .hotspots .number { color: #fd7e14; }
  .ncloc .number    { color: #17a2b8; }
  .gate {
    background: white; border-radius: 8px; padding: 20px;
    margin: 20px 0; font-size: 22px; font-weight: bold;
    text-align: center; box-shadow: 0 2px 6px rgba(0,0,0,0.1);
    color: ${STATUS_COLOR};
  }
  table {
    width: 100%; border-collapse: collapse;
    background: white; border-radius: 8px;
    overflow: hidden; box-shadow: 0 2px 6px rgba(0,0,0,0.1);
    margin-top: 20px;
  }
  th { background: #4e9bcd; color: white; padding: 12px; text-align: left; }
  td { padding: 10px 12px; border-bottom: 1px solid #eee; }
  tr:hover { background: #f9f9f9; }
  .CRITICAL { color: #dc3545; font-weight: bold; }
  .MAJOR    { color: #ff6b35; font-weight: bold; }
  .MINOR    { color: #ffc107; }
  .INFO     { color: #17a2b8; }
  .btn {
    display: inline-block; background: #4e9bcd; color: white;
    padding: 10px 20px; border-radius: 5px;
    text-decoration: none; margin-top: 15px;
  }
</style>
</head>
<body>

<h1>🔍 SonarQube Security & Code Report</h1>
<p><strong>Project:</strong> my-app &nbsp;|&nbsp;
   <strong>Build:</strong> #${BUILD_NUMBER} &nbsp;|&nbsp;
   <strong>Date:</strong> $(date '+%Y-%m-%d %H:%M:%S')</p>

<div class="gate">Quality Gate: ${STATUS_ICON}</div>

<div class="summary">
  <div class="card bugs">
    <div class="number">${BUGS}</div>
    <div class="label">🐛 Bugs</div>
  </div>
  <div class="card vulns">
    <div class="number">${VULNS}</div>
    <div class="label">🔓 Vulnerabilities</div>
  </div>
  <div class="card smells">
    <div class="number">${SMELLS}</div>
    <div class="label">💨 Code Smells</div>
  </div>
  <div class="card hotspots">
    <div class="number">${HOTSPOTS}</div>
    <div class="label">🔥 Security Hotspots</div>
  </div>
  <div class="card ncloc">
    <div class="number">${NCLOC}</div>
    <div class="label">📝 Lines of Code</div>
  </div>
</div>

<h2>🔓 Vulnerabilities (Critical & Major)</h2>
<table>
  <tr>
    <th>Severity</th>
    <th>Component</th>
    <th>Message</th>
    <th>Line</th>
  </tr>
  $(cat /tmp/sonar-vulns.json | grep -o '"severity":"[^"]*","message":"[^"]*","component":"[^"]*"' | \
    awk -F'"' '{
      sev=$4; msg=$8; comp=$12;
      gsub(/.*:/, "", comp);
      print "<tr><td class=\""sev"\">"sev"</td><td>"comp"</td><td>"msg"</td><td>-</td></tr>"
    }' || echo "<tr><td colspan=4>No vulnerabilities found ✅</td></tr>")
</table>

<h2>🐛 Bugs (Critical & Major)</h2>
<table>
  <tr>
    <th>Severity</th>
    <th>Component</th>
    <th>Message</th>
  </tr>
  $(cat /tmp/sonar-bugs.json | grep -o '"severity":"[^"]*","message":"[^"]*","component":"[^"]*"' | \
    awk -F'"' '{
      sev=$4; msg=$8; comp=$12;
      gsub(/.*:/, "", comp);
      print "<tr><td class=\""sev"\">"sev"</td><td>"comp"</td><td>"msg"</td></tr>"
    }' || echo "<tr><td colspan=3>No critical bugs found ✅</td></tr>")
</table>

<br>
<a class="btn" href="http://192.168.237.148:9000/dashboard?id=my-app"
   target="_blank">📊 Open Full SonarQube Dashboard</a>
&nbsp;
<a class="btn" href="http://192.168.237.148:9000/project/issues?id=my-app&types=VULNERABILITY"
   target="_blank">🔓 All Vulnerabilities</a>
&nbsp;
<a class="btn" href="http://192.168.237.148:9000/security_hotspots?id=my-app"
   target="_blank">🔥 Security Hotspots</a>

</body>
</html>
HTMLEOF

                echo "Report generated: sonarqube-report.html"
            '''
        }

        archiveArtifacts artifacts: 'sonarqube-report.html',
                         fingerprint: true

        publishHTML(target: [
            allowMissing:          false,
            alwaysLinkToLastBuild: true,
            keepAll:               true,
            reportDir:             '.',
            reportFiles:           'sonarqube-report.html',
            reportName:            'SonarQube Report',
            reportTitles:          'Code & Security Analysis'
        ])
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
                    -config api.addrs.addr.regex=true &

                echo "Waiting for ZAP daemon..."
                for i in \$(seq 1 40); do
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