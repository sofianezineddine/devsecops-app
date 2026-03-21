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

        stage('Falco — Runtime Security') {
    steps {
        sh '''
            echo "=========================================="
            echo "  Falco Runtime Security Monitoring"
            echo "=========================================="
            echo "Waiting 30 seconds to collect runtime events..."
            sleep 30

            LOG=/var/log/falco/falco_events.log

            # Check if Falco log exists
            if [ ! -f "$LOG" ]; then
                echo "WARNING: Falco log not found at $LOG"
                echo "Make sure Falco is running and log is mounted"
                cat > falco-report.html << 'HTMLEOF'
<!DOCTYPE html>
<html>
<body style="font-family:Arial;padding:20px;">
<h2>⚠️ Falco Not Available</h2>
<p>Falco log file not found. Make sure:</p>
<ul>
  <li>Falco service is running: <code>sudo systemctl status falco-modern-bpf</code></li>
  <li>Log file exists: <code>/var/log/falco/falco_events.log</code></li>
  <li>Volume is mounted in Jenkins container</li>
</ul>
</body>
</html>
HTMLEOF
                exit 0
            fi

            # Count events by priority
            TOTAL=$(wc -l < "$LOG" 2>/dev/null || echo 0)
            CRITICAL=$(grep -ci '"priority":"Critical"' "$LOG" 2>/dev/null || echo 0)
            ERROR=$(grep -ci '"priority":"Error"' "$LOG" 2>/dev/null || echo 0)
            WARNING=$(grep -ci '"priority":"Warning"' "$LOG" 2>/dev/null || echo 0)
            NOTICE=$(grep -ci '"priority":"Notice"' "$LOG" 2>/dev/null || echo 0)
            INFO=$(grep -ci '"priority":"Informational"' "$LOG" 2>/dev/null || echo 0)

            # Get last 10 events for report
            LAST_EVENTS=$(tail -10 "$LOG" 2>/dev/null || echo "No events")

            echo ""
            echo "=== Falco Runtime Security Summary ==="
            echo "Total events:  $TOTAL"
            echo "Critical:      $CRITICAL"
            echo "Error:         $ERROR"
            echo "Warning:       $WARNING"
            echo "Notice:        $NOTICE"
            echo ""

            # Determine overall status
            if [ "$CRITICAL" -gt 0 ]; then
                OVERALL_STATUS="CRITICAL"
                STATUS_COLOR="#dc3545"
                STATUS_ICON="🚨"
            elif [ "$ERROR" -gt 0 ]; then
                OVERALL_STATUS="ERROR"
                STATUS_COLOR="#ff6b35"
                STATUS_ICON="❌"
            elif [ "$WARNING" -gt 0 ]; then
                OVERALL_STATUS="WARNING"
                STATUS_COLOR="#ffc107"
                STATUS_ICON="⚠️"
            else
                OVERALL_STATUS="CLEAN"
                STATUS_COLOR="#28a745"
                STATUS_ICON="✅"
            fi

            echo "Overall Status: $STATUS_ICON $OVERALL_STATUS"

            # Generate HTML report
            cat > falco-report.html << HTMLEOF
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Falco Runtime Security Report</title>
<style>
  body {
    font-family: Arial, sans-serif;
    margin: 0; padding: 20px;
    background: #f5f5f5;
  }
  h1 { color: #333; border-bottom: 3px solid #e74c3c; padding-bottom: 10px; }
  h2 { color: #555; margin-top: 30px; }
  .status-banner {
    background: ${STATUS_COLOR};
    color: white;
    padding: 20px;
    border-radius: 8px;
    font-size: 24px;
    font-weight: bold;
    text-align: center;
    margin: 20px 0;
  }
  .summary {
    display: flex;
    flex-wrap: wrap;
    gap: 15px;
    margin: 20px 0;
  }
  .card {
    background: white;
    border-radius: 8px;
    padding: 20px;
    min-width: 130px;
    text-align: center;
    box-shadow: 0 2px 6px rgba(0,0,0,0.1);
  }
  .card .number { font-size: 42px; font-weight: bold; }
  .card .label  { font-size: 13px; color: #666; margin-top: 5px; }
  .total    .number { color: #333; }
  .critical .number { color: #dc3545; }
  .error    .number { color: #ff6b35; }
  .warning  .number { color: #ffc107; }
  .notice   .number { color: #17a2b8; }
  .info     .number { color: #6c757d; }
  table {
    width: 100%;
    border-collapse: collapse;
    background: white;
    border-radius: 8px;
    overflow: hidden;
    box-shadow: 0 2px 6px rgba(0,0,0,0.1);
    margin-top: 15px;
  }
  th {
    background: #e74c3c;
    color: white;
    padding: 12px;
    text-align: left;
    font-size: 14px;
  }
  td {
    padding: 10px 12px;
    border-bottom: 1px solid #eee;
    font-size: 13px;
    word-break: break-all;
  }
  tr:hover { background: #f9f9f9; }
  .Critical     { color: #dc3545; font-weight: bold; }
  .Error        { color: #ff6b35; font-weight: bold; }
  .Warning      { color: #ffc107; font-weight: bold; }
  .Notice       { color: #17a2b8; }
  .Informational{ color: #6c757d; }
  .no-events { color: #28a745; font-style: italic; padding: 15px; }
</style>
</head>
<body>

<h1>🛡️ Falco Runtime Security Report</h1>

<p>
  <strong>Project:</strong> my-app &nbsp;|&nbsp;
  <strong>Build:</strong> #${BUILD_NUMBER} &nbsp;|&nbsp;
  <strong>Date:</strong> $(date '+%Y-%m-%d %H:%M:%S') &nbsp;|&nbsp;
  <strong>Namespace:</strong> webapps
</p>

<div class="status-banner">
  ${STATUS_ICON} Runtime Security Status: ${OVERALL_STATUS}
</div>

<div class="summary">
  <div class="card total">
    <div class="number">$TOTAL</div>
    <div class="label">📊 Total Events</div>
  </div>
  <div class="card critical">
    <div class="number">$CRITICAL</div>
    <div class="label">🚨 Critical</div>
  </div>
  <div class="card error">
    <div class="number">$ERROR</div>
    <div class="label">❌ Error</div>
  </div>
  <div class="card warning">
    <div class="number">$WARNING</div>
    <div class="label">⚠️ Warning</div>
  </div>
  <div class="card notice">
    <div class="number">$NOTICE</div>
    <div class="label">ℹ️ Notice</div>
  </div>
</div>

<h2>🚨 Critical Events</h2>
<table>
  <tr><th>Time</th><th>Rule</th><th>Output</th></tr>
  $(grep -i '"priority":"Critical"' "$LOG" 2>/dev/null | tail -20 | \
    awk -F'"' '{
      time=""; rule=""; output="";
      for(i=1;i<=NF;i++) {
        if($i=="time") time=$(i+2);
        if($i=="rule") rule=$(i+2);
        if($i=="output") output=$(i+2);
      }
      print "<tr><td>"time"</td><td class=\"Critical\">"rule"</td><td>"output"</td></tr>"
    }' || echo '<tr><td colspan="3" class="no-events">✅ No critical events detected</td></tr>')
</table>

<h2>❌ Error Events</h2>
<table>
  <tr><th>Time</th><th>Rule</th><th>Output</th></tr>
  $(grep -i '"priority":"Error"' "$LOG" 2>/dev/null | tail -20 | \
    awk -F'"' '{
      time=""; rule=""; output="";
      for(i=1;i<=NF;i++) {
        if($i=="time") time=$(i+2);
        if($i=="rule") rule=$(i+2);
        if($i=="output") output=$(i+2);
      }
      print "<tr><td>"time"</td><td class=\"Error\">"rule"</td><td>"output"</td></tr>"
    }' || echo '<tr><td colspan="3" class="no-events">✅ No error events detected</td></tr>')
</table>

<h2>⚠️ Warning Events</h2>
<table>
  <tr><th>Time</th><th>Rule</th><th>Output</th></tr>
  $(grep -i '"priority":"Warning"' "$LOG" 2>/dev/null | tail -20 | \
    awk -F'"' '{
      time=""; rule=""; output="";
      for(i=1;i<=NF;i++) {
        if($i=="time") time=$(i+2);
        if($i=="rule") rule=$(i+2);
        if($i=="output") output=$(i+2);
      }
      print "<tr><td>"time"</td><td class=\"Warning\">"rule"</td><td>"output"</td></tr>"
    }' || echo '<tr><td colspan="3" class="no-events">✅ No warning events detected</td></tr>')
</table>

<h2>📋 Last 10 Raw Events</h2>
<table>
  <tr><th>Raw Event</th></tr>
  $(tail -10 "$LOG" 2>/dev/null | \
    awk '{print "<tr><td style=\"font-size:11px;font-family:monospace;\">"$0"</td></tr>"}' || \
    echo '<tr><td>No events</td></tr>')
</table>

</body>
</html>
HTMLEOF

            echo "Falco HTML report generated successfully"

            # Save JSON summary
            cat > falco-report.json << EOF
{
  "build": "${BUILD_NUMBER}",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "status": "${OVERALL_STATUS}",
  "total": ${TOTAL},
  "critical": ${CRITICAL},
  "error": ${ERROR},
  "warning": ${WARNING},
  "notice": ${NOTICE}
}
EOF

            # Fail build on CRITICAL events
            if [ "$CRITICAL" -gt 0 ]; then
                echo ""
                echo "🚨 BUILD FAILED: $CRITICAL CRITICAL runtime security events detected!"
                exit 1
            fi

            echo "✅ No critical runtime security events detected"
        '''

        archiveArtifacts artifacts: 'falco-report.html, falco-report.json',
                         fingerprint: true

        publishHTML(target: [
            allowMissing:          false,
            alwaysLinkToLastBuild: true,
            keepAll:               true,
            reportDir:             '.',
            reportFiles:           'falco-report.html',
            reportName:            'Falco Runtime Security',
            reportTitles:          'Runtime Security Report'
        ])
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