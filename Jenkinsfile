pipeline {
    agent any

    options {
        timestamps()                          // Show timestamps in logs
        timeout(time: 60, unit: 'MINUTES')    // Kill if pipeline hangs
        buildDiscarder(logRotator(            // Keep only last 10 builds
            numToKeepStr: '10',
            artifactNumToKeepStr: '5'
        ))
        disableConcurrentBuilds()             // Prevent parallel builds
    }

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
        // Maven cache in jenkins_home (persists across builds)
        MAVEN_OPTS   = '-Dmaven.repo.local=/var/jenkins_home/.m2/repository'
    }

    triggers { githubPush() }

    stages {

        stage('Checkout') {
            steps {
                script {
                    try {
                        git branch: 'master',
                            url: 'https://github.com/sofianezineddine/devsecops-app.git',
                            credentialsId: 'git-cred-test'
                        env.STAGE_CHECKOUT = 'SUCCESS'
                    } catch(e) {
                        env.STAGE_CHECKOUT = "FAILED: ${e.message?.take(100)}"
                        throw e
                    }
                }
            }
        }

        stage('Build & Test') {
            steps {
                script {
                    try {
                        // --batch-mode: no progress bars = faster logs
                        sh 'mvn clean verify --batch-mode -q'
                        env.STAGE_BUILD = 'SUCCESS'
                    } catch(e) {
                        env.STAGE_BUILD = "FAILED: ${e.message?.take(100)}"
                        throw e
                    }
                }
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
                script {
                    try {
                        withSonarQubeEnv('sonar') {
                            sh """
                                sonar-scanner \
                                  -Dsonar.projectKey=my-app \
                                  -Dsonar.sources=src/main/java \
                                  -Dsonar.tests=src/test/java \
                                  -Dsonar.java.binaries=target/classes
                            """
                        }
                        env.STAGE_SONAR = 'SUCCESS'
                    } catch(e) {
                        env.STAGE_SONAR = "FAILED: ${e.message?.take(100)}"
                        throw e
                    }
                }
            }
        }

        stage('Quality Gate') {
            steps {
                script {
                    try {
                        timeout(time: 5, unit: 'MINUTES') {
                            waitForQualityGate abortPipeline: true
                        }
                        env.STAGE_QUALITY = 'SUCCESS'
                    } catch(e) {
                        env.STAGE_QUALITY = "FAILED: ${e.message?.take(100)}"
                        throw e
                    }
                }
            }
        }

        stage('Publish to Nexus') {
            steps {
                script {
                    try {
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
                                mvn deploy -DskipTests --batch-mode -q \
                                    -s /tmp/nexus-settings.xml
                            '''
                        }
                        env.STAGE_NEXUS = 'SUCCESS'
                    } catch(e) {
                        env.STAGE_NEXUS = "FAILED: ${e.message?.take(100)}"
                        throw e
                    }
                }
            }
        }

        stage('Build & Push Docker Image') {
            steps {
                script {
                    try {
                        withCredentials([usernamePassword(
                            credentialsId: 'docker-cred',
                            usernameVariable: 'DOCKER_USER',
                            passwordVariable: 'DOCKER_PASS')]) {
                            sh """
                                echo "\$DOCKER_PASS" | docker login \
                                    -u "\$DOCKER_USER" --password-stdin

                                docker buildx inspect jenkins-builder > /dev/null 2>&1 \
                                    || docker buildx create \
                                        --name jenkins-builder \
                                        --driver docker-container \
                                        --bootstrap

                                docker buildx use jenkins-builder

                                docker buildx build \
                                    --platform linux/amd64 \
                                    --tag \${DOCKER_USER}/my-app:${BUILD_NUMBER} \
                                    --tag \${DOCKER_USER}/my-app:latest \
                                    --cache-from type=registry,ref=\${DOCKER_USER}/my-app:cache \
                                    --cache-to   type=registry,ref=\${DOCKER_USER}/my-app:cache,mode=max \
                                    --push .

                                docker logout
                            """
                        }
                        env.STAGE_DOCKER = 'SUCCESS'
                    } catch(e) {
                        env.STAGE_DOCKER = "FAILED: ${e.message?.take(100)}"
                        throw e
                    }
                }
            }
        }

        // ── PARALLEL: Trivy scan + K8s deploy run at the same time ──
        stage('Security Scan & Deploy') {
            parallel {

                stage('Trivy Image Scan') {
                    steps {
                        script {
                            try {
                                sh """
                                    if [ ! -d "\$HOME/.cache/trivy/java-db" ]; then
                                        TRIVY_JAVA_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-java-db:1 \
                                        trivy image --download-java-db-only
                                    fi

                                    if [ ! -f /tmp/trivy-html.tpl ]; then
                                        curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/html.tpl \
                                          -o /tmp/trivy-html.tpl
                                    fi

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
                                """
                                env.STAGE_TRIVY = 'SUCCESS'
                            } catch(e) {
                                env.STAGE_TRIVY = "FAILED: ${e.message?.take(100)}"
                                throw e
                            }
                        }
                        archiveArtifacts artifacts: 'trivy-report.html',
                                         fingerprint: true
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

                stage('Render & Deploy to Kubernetes') {
                    steps {
                        script {
                            try {
                                sh """
                                    export IMG_TAG="${IMAGE_NAME}:${BUILD_NUMBER}"
                                    envsubst < /root/k8s-manifest/deployment.yaml \
                                             > rendered-deployment.yaml
                                """
                                env.STAGE_MANIFEST = 'SUCCESS'
                            } catch(e) {
                                env.STAGE_MANIFEST = "FAILED: ${e.message?.take(100)}"
                                throw e
                            }
                        }
                        archiveArtifacts artifacts: 'rendered-deployment.yaml',
                                         fingerprint: true
                        script {
                            try {
                                withKubeCredentials(kubectlCredentials: [[
                                    caCertificate: '',
                                    clusterName:   'devsecops-cluster',
                                    contextName:   '',
                                    credentialsId: 'k8s-cred',
                                    namespace:     'webapps',
                                    serverUrl:     'https://192.168.237.148:6443'
                                ]]) {
                                    sh 'kubectl apply -f rendered-deployment.yaml'
                                    sh 'kubectl rollout status deployment/my-app -n webapps --timeout=120s'
                                    sh 'kubectl get pods -n webapps'
                                }
                                env.STAGE_K8S = 'SUCCESS'
                            } catch(e) {
                                env.STAGE_K8S = "FAILED: ${e.message?.take(100)}"
                                throw e
                            }
                        }
                    }
                }
            }
        }

        stage('OWASP ZAP — DAST Scan') {
            steps {
                script {
                    try {
                        // Reduced from 15s to 5s — app is already running
                        sh 'sleep 5'

                        def nodePort = sh(
                            script: "kubectl get svc my-app-service -n webapps -o jsonpath='{.spec.ports[0].nodePort}'",
                            returnStdout: true
                        ).trim()

                        def appUrl = "http://192.168.237.148:${nodePort}"
                        echo "Scanning: ${appUrl}"

                        sh """
                            mkdir -p /tmp/zap-reports
                            pkill -f "zap.sh" || true
                            sleep 2

                            # Start ZAP with silent mode + no autoupdate
                            zap.sh -daemon \
                                -host 127.0.0.1 \
                                -port 8090 \
                                -config api.disablekey=true \
                                -config api.addrs.addr.name=.* \
                                -config api.addrs.addr.regex=true \
                                -config api.autoupdate=false \
                                -silent &

                            # Poll until ready (max 3 min)
                            echo "Waiting for ZAP..."
                            for i in \$(seq 1 60); do
                                curl -s http://127.0.0.1:8090/JSON/core/view/version/ \
                                    > /dev/null 2>&1 && echo "ZAP ready at attempt \$i!" && break
                                sleep 3
                            done

                            # Spider
                            curl -s "http://127.0.0.1:8090/JSON/spider/action/scan/?url=${appUrl}&maxChildren=10"
                            sleep 15

                            # Active scan
                            curl -s "http://127.0.0.1:8090/JSON/ascan/action/scan/?url=${appUrl}&recurse=true"
                            sleep 60

                            # Generate reports
                            curl -s "http://127.0.0.1:8090/OTHER/core/other/htmlreport/" \
                                -o /tmp/zap-reports/zap-report.html
                            curl -s "http://127.0.0.1:8090/JSON/core/view/alerts/?baseurl=${appUrl}&start=0&count=200" \
                                -o /tmp/zap-reports/zap-alerts.json

                            cp /tmp/zap-reports/zap-report.html zap-report.html
                            cp /tmp/zap-reports/zap-alerts.json zap-alerts.json

                            curl -s "http://127.0.0.1:8090/JSON/core/action/shutdown/" || true
                        """

                        // Parse ZAP results
                        def high = sh(
                            script: "grep -o '\"risk\":\"High\"' /tmp/zap-reports/zap-alerts.json 2>/dev/null | wc -l || echo 0",
                            returnStdout: true
                        ).trim()
                        def med = sh(
                            script: "grep -o '\"risk\":\"Medium\"' /tmp/zap-reports/zap-alerts.json 2>/dev/null | wc -l || echo 0",
                            returnStdout: true
                        ).trim()
                        def low = sh(
                            script: "grep -o '\"risk\":\"Low\"' /tmp/zap-reports/zap-alerts.json 2>/dev/null | wc -l || echo 0",
                            returnStdout: true
                        ).trim()

                        echo "ZAP Results — High: ${high} | Medium: ${med} | Low: ${low}"
                        env.STAGE_ZAP = "SUCCESS (High:${high} Med:${med} Low:${low})"

                    } catch(e) {
                        env.STAGE_ZAP = "FAILED: ${e.message?.take(100)}"
                        throw e
                    }
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
            }
        }
    }

    post {
        always {
            script {
                def stages = [
                    ['Checkout',             env.STAGE_CHECKOUT ?: 'SKIPPED'],
                    ['Build & Test',         env.STAGE_BUILD    ?: 'SKIPPED'],
                    ['SonarQube Analysis',   env.STAGE_SONAR    ?: 'SKIPPED'],
                    ['Quality Gate',         env.STAGE_QUALITY  ?: 'SKIPPED'],
                    ['Publish to Nexus',     env.STAGE_NEXUS    ?: 'SKIPPED'],
                    ['Build & Push Docker',  env.STAGE_DOCKER   ?: 'SKIPPED'],
                    ['Trivy Image Scan',     env.STAGE_TRIVY    ?: 'SKIPPED'],
                    ['Render K8s Manifest',  env.STAGE_MANIFEST ?: 'SKIPPED'],
                    ['Deploy to Kubernetes', env.STAGE_K8S      ?: 'SKIPPED'],
                    ['OWASP ZAP DAST Scan',  env.STAGE_ZAP      ?: 'SKIPPED']
                ]

                def stageRows = ''
                stages.each { s ->
                    def name      = s[0]
                    def status    = s[1]
                    def isSuccess = status.startsWith('SUCCESS')
                    def isSkipped = status == 'SKIPPED'
                    def color     = isSuccess ? '#28a745' :
                                    isSkipped ? '#6c757d' : '#dc3545'
                    def icon      = isSuccess ? '✅' :
                                    isSkipped ? '⏭️' : '❌'
                    def label     = isSuccess ? status :
                                    isSkipped ? 'SKIPPED' : 'FAILED'
                    def detail    = (!isSuccess && !isSkipped) ?
                        "<br><small style='color:#dc3545;font-size:11px;'>${status}</small>" : ''

                    stageRows += """
                        <tr>
                          <td style='padding:10px 15px;border-bottom:1px solid #eee;
                                     font-weight:500;'>${icon} ${name}</td>
                          <td style='padding:10px 15px;border-bottom:1px solid #eee;
                                     color:${color};font-weight:bold;'>
                            ${label}${detail}
                          </td>
                        </tr>
                    """
                }

                def overallColor = currentBuild.result == 'SUCCESS' ? '#28a745' :
                                   currentBuild.result == 'FAILURE' ? '#dc3545' :
                                   currentBuild.result == 'UNSTABLE'? '#ffc107' : '#6c757d'
                def overallIcon  = currentBuild.result == 'SUCCESS' ? '✅' :
                                   currentBuild.result == 'FAILURE' ? '❌' :
                                   currentBuild.result == 'UNSTABLE'? '⚠️' : '⛔'

                emailext(
                    subject: "${overallIcon} [${currentBuild.result}] ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                    to: 'sofianezineddine77@gmail.com',
                    mimeType: 'text/html',
                    body: """
<!DOCTYPE html>
<html>
<body style="font-family:Arial,sans-serif;background:#f5f5f5;padding:20px;">
<div style="max-width:650px;margin:0 auto;background:white;
            border-radius:8px;overflow:hidden;
            box-shadow:0 2px 10px rgba(0,0,0,0.1);">

  <div style="background:${overallColor};padding:25px;text-align:center;">
    <h1 style="color:white;margin:0;font-size:22px;">
      ${overallIcon} Pipeline ${currentBuild.result}
    </h1>
    <p style="color:rgba(255,255,255,0.9);margin:8px 0 0;">
      ${env.JOB_NAME} — Build #${env.BUILD_NUMBER}
    </p>
  </div>

  <div style="padding:20px;background:#f8f9fa;border-bottom:1px solid #dee2e6;">
    <table style="width:100%;">
      <tr>
        <td style="padding:4px 0;">
          <strong>⏱️ Duration:</strong> ${currentBuild.durationString}
        </td>
        <td style="padding:4px 0;">
          <strong>🌿 Branch:</strong> master
        </td>
      </tr>
      <tr>
        <td colspan="2" style="padding-top:8px;">
          <strong>🔗 Build URL:</strong>
          <a href="${env.BUILD_URL}" style="color:#4e9bcd;">${env.BUILD_URL}</a>
        </td>
      </tr>
    </table>
  </div>

  <div style="padding:20px;">
    <h2 style="color:#333;margin-top:0;font-size:16px;
               border-bottom:2px solid #dee2e6;padding-bottom:8px;">
      📊 Pipeline Stage Results
    </h2>
    <table style="width:100%;border-collapse:collapse;
                  box-shadow:0 1px 3px rgba(0,0,0,0.1);
                  border-radius:6px;overflow:hidden;">
      <thead>
        <tr style="background:#343a40;color:white;">
          <th style="padding:10px 15px;text-align:left;width:55%;">Stage</th>
          <th style="padding:10px 15px;text-align:left;">Status / Details</th>
        </tr>
      </thead>
      <tbody>${stageRows}</tbody>
    </table>
  </div>

  <div style="padding:0 20px 20px;">
    <h2 style="color:#333;font-size:16px;
               border-bottom:2px solid #dee2e6;padding-bottom:8px;">
      🔒 Security Reports
    </h2>
    <table style="width:100%;">
      <tr>
        <td style="padding:5px;">
          <a href="${env.BUILD_URL}Trivy_20Security_20Report"
             style="background:#17a2b8;color:white;padding:8px 14px;
                    border-radius:4px;text-decoration:none;
                    font-size:13px;display:inline-block;">
            🐳 Trivy
          </a>
        </td>
        <td style="padding:5px;">
          <a href="${env.BUILD_URL}OWASP_20ZAP_20Security_20Report"
             style="background:#e74c3c;color:white;padding:8px 14px;
                    border-radius:4px;text-decoration:none;
                    font-size:13px;display:inline-block;">
            🕷️ ZAP
          </a>
        </td>
        <td style="padding:5px;">
          <a href="http://192.168.237.148:9000/dashboard?id=my-app"
             style="background:#4e9bcd;color:white;padding:8px 14px;
                    border-radius:4px;text-decoration:none;
                    font-size:13px;display:inline-block;">
            📊 SonarQube
          </a>
        </td>
        <td style="padding:5px;">
          <a href="http://192.168.237.148:3000/d/falco-runtime"
             style="background:#e74c3c;color:white;padding:8px 14px;
                    border-radius:4px;text-decoration:none;
                    font-size:13px;display:inline-block;">
            🛡️ Falco
          </a>
        </td>
      </tr>
    </table>
  </div>

  <div style="background:#343a40;color:#adb5bd;padding:12px;
              text-align:center;font-size:11px;">
    DevSecOps Pipeline | Jenkins CI/CD |
    ${new Date().format('yyyy-MM-dd HH:mm:ss')}
  </div>

</div>
</body>
</html>
                    """
                )
            }
        }
    }
}