#!groovy
def container_id
def container_port
def CONTAINER_NAME="my_application2"
def CONTAINER_TAG="latest"
def DOCKER_HUB_USER="dobriynoinogda"
def DOCKER_HUB_PASSWORD="xxxxxxxxxxxxxxxxxxxxxxxxxx"
def JENKINS_IP="192.168.0.34"

pipeline {
    agent any
    stages {
        stage("Init") {
            steps {
              checkoutFromGit()         
              echo 'Init is complete'
            }
        }
        stage("Build") {
            steps {
              buildApp()
              buildDockerImage(CONTAINER_NAME, CONTAINER_TAG, DOCKER_HUB_USER)
              echo 'Build is complete'
            }
            post {
                success {
                  archiveArtifacts 'webgoat-server/target/webgoat-server-*.jar'
                }
            }
        }
        stage("Audit") {
            steps {
              auditWithDockerBench(CONTAINER_NAME, CONTAINER_TAG, DOCKER_HUB_USER)
              echo 'Audit is complete'
            }
        }
        stage("SAST") {
            steps {
              runSonarTest(JENKINS_IP)
              checkImageWithClair(JENKINS_IP, CONTAINER_NAME, CONTAINER_TAG, DOCKER_HUB_USER)
              echo 'SAST is complete'
            }
        }
        stage("SCA") {
            steps {
              dependencyCheck()
              echo 'SCA is complete'
            }
        }
        stage("DAST") {
            steps {
              startDockerContainer(CONTAINER_NAME, CONTAINER_TAG, DOCKER_HUB_USER)
              doZapBaselineCheck(JENKINS_IP)
              echo 'DAST is complete'
            }
            post {
                always {
                  stopDockerContainer()                  
                 }
            }
        }
        stage('Post'){
            steps {
              pushImageToHub(CONTAINER_NAME, CONTAINER_TAG, DOCKER_HUB_USER, DOCKER_HUB_PASSWORD)
              echo 'Post is complete'
            }
            post {
                always {
                  archiveArtifacts 'reports/*'
                }
            }
        }
    }
}


// ================================================================================================
// Initialization steps
// ================================================================================================

def checkoutFromGit() {
    checkout([$class: 'GitSCM', branches: [[name: '*/master']], 
      userRemoteConfigs: [[url: 'https://github.com/WebGoat/WebGoat/']]])
    echo 'Checkout is complete'
}

// ================================================================================================
// Build steps
// ================================================================================================

def buildApp() {
    withDockerContainer("maven:3.6.3-jdk-11") { sh "mvn clean install" }
}

def buildDockerImage(containerName, tag, dockerUser) {
    sh "cp /opt/dockerfiles/* ./"
    sh "docker build --no-cache -t $dockerUser/$containerName:$tag ."
}

def startDockerContainer(containerName, tag, dockerUser) {
    container_port = sh returnStdout: true, script: 'python3 -c \
        \'import socket; \
        s=socket.socket(); \
        s.bind(("", 0)); \
        print(s.getsockname()[1], end=""); \
        s.close()\''
    container_id = sh returnStdout: true, script: "docker run -d \
        -p ${container_port}:8080 $dockerUser/$containerName:$tag"
}

def stopDockerContainer() {
    sh "docker stop ${container_id}"
}

// ================================================================================================
// Test steps
// ================================================================================================

def auditWithDockerBench(containerName, tag, dockerUser) {
    dir ('reports') {
      sh "docker run --net host --pid host --cap-add audit_control \
          -v /var/lib:/var/lib \
          -v /var/run/docker.sock:/var/run/docker.sock \
          -v /usr/lib/systemd:/usr/lib/systemd \
          -v /etc:/etc --label docker_bench_security \
          docker/docker-bench-security \
          -t $dockerUser/$containerName:$tag \
          > bench_report.txt"
    }
}    

def doZapBaselineCheck(jenkinsIP) {
    dir ('reports') {
      sh 'chmod -R 777 .'
      sh "docker run --rm -t \
          -v ${WORKSPACE}/reports:/zap/wrk/ \
          owasp/zap2docker-stable zap-baseline.py \
          -t http://${jenkinsIP}:${container_port}/WebGoat \
          -r _zap-report.html || true"
      // Fix access rights
      sh """
         cp _zap-report.html zap-report.html
         rm -r _zap-report.html
         """
    }
}

def dependencyCheck() {
    sh "mkdir ${WORKSPACE}/data || true"
    sh "mkdir ${WORKSPACE}/reports || true"
    sh "docker run --rm \
        -e user=jenkins \
        -u 112:117 \
        --volume ${WORKSPACE}:/src:z \
        --volume ${WORKSPACE}/data:/usr/share/dependency-check/data:z \
        --volume ${WORKSPACE}/reports:/report:z \
        owasp/dependency-check:latest \
        --scan /src \
        --out /report"
}

def checkImageWithClair(jenkinsIP, containerName, tag, dockerUser) {
    dir ('reports') {
      clair_db_container_id = sh returnStdout: true, script: 'docker run -d \
        --name db \
        arminc/clair-db:latest'
      sh "sleep 15" // Wait for db to come up
      clair_local_scan_container_id = sh returnStdout: true, script: 'docker run -d \
        -p 6060:6060 \
        --link db:postgres \
        --name clair arminc/clair-local-scan'
      sh "/opt/clair-scanner_linux_amd64 \
          --ip='${jenkinsIP}' \
          --report='clair-report.json' \
          $dockerUser/$containerName:$tag \
          > /dev/null 2>&1 || true"
      sh """
         docker stop ${clair_db_container_id}
         docker rm ${clair_db_container_id}
         docker stop ${clair_local_scan_container_id}
         docker rm ${clair_local_scan_container_id}
         """
      }
}

def runSonarTest(jenkinsIP) {
    withDockerContainer("maven:3.6.3-jdk-11")  {
        sh "mvn sonar:sonar -Dsonar.host.url=http://${jenkinsIP}:9000"
    }
}

// ================================================================================================
// Post steps
// ================================================================================================

def pushImageToHub(containerName, tag, dockerUser, dockerPassword) {
    sh """
       docker login -u $dockerUser -p $dockerPassword
       docker tag  $dockerUser/$containerName $dockerUser/$containerName:$tag
       docker push $dockerUser/$containerName:$tag
       docker logout
       """
    echo "Image push is complete"
}

