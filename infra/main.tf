# SceneLog EC2 인프라 (Terraform)
#
# 범위를 의도적으로 최소로 유지한다 — day6의 목표는 "접속되는 URL 하나"다.
#   만드는 것: 보안그룹 1개 + t3.micro 1대 (기본 VPC 사용)
#   안 만드는 것: RDS·ALB·도메인·Auto Scaling (README '의도적으로 하지 않은 것' 참고)
#
# 상태 파일(terraform.tfstate)은 로컬 보관, 커밋 금지 (.gitignore).
# 실행: terraform init && terraform apply

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-2" # 서울
}

variable "my_ip" {
  description = "SSH(22)를 허용할 내 공인 IP (x.x.x.x/32)"
  type        = string
}

variable "key_name" {
  description = "EC2 키페어 이름 (기존 키 재사용)"
  type        = string
  default     = "devmatch-key"
}

# 항상 최신 AL2023 AMI를 SSM 공개 파라미터에서 가져온다 (하드코딩 방지)
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

data "aws_vpc" "default" {
  default = true
}

resource "aws_security_group" "scenelog" {
  name        = "scenelog-sg"
  description = "SceneLog: 8080 public, SSH from my IP only"
  vpc_id      = data.aws_vpc.default.id

  # 앱 포트만 공개 — DB 포트(5432/27017/6379)는 열지 않는다.
  # DB는 컨테이너로 앱과 같은 호스트에 있고, 외부에서 접근할 이유가 없다.
  ingress {
    description = "app (HTTP)"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "SSH from my IP"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.my_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "scenelog-sg" }
}

resource "aws_instance" "scenelog" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = "t3.micro" # RAM 1GB — OOM 대응은 user_data의 swap 2GB
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.scenelog.id]

  # 시크릿은 user_data에 넣지 않는다 (.env는 SSH로 별도 전달).
  # 여기서는 재부팅과 무관하게 한 번만 필요한 준비 작업만 한다.
  user_data = <<-EOF
    #!/bin/bash
    set -eux
    # swap 2GB — t3.micro에서 컨테이너 4개 + 이미지 빌드의 생존 조건 (day6 계획서)
    dd if=/dev/zero of=/swapfile bs=1M count=2048
    chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
    # Docker + git
    dnf install -y docker git
    systemctl enable --now docker
    usermod -aG docker ec2-user
    # docker compose v2 플러그인 (AL2023 dnf에는 없음)
    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -SL https://github.com/docker/compose/releases/download/v2.32.4/docker-compose-linux-x86_64 \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
    # 저장소 clone (public)
    sudo -u ec2-user git clone https://github.com/aucu2005/scenelog.git /home/ec2-user/scenelog
  EOF

  tags = { Name = "scenelog" }
}

# 고정 공인 IP (Elastic IP) — stop/start를 해도 주소가 유지된다.
# 기본 공인 IP는 중지 시 반납되는 "임대 번호"라 URL이 매번 바뀐다 —
# 서류에 적을 URL은 계정이 소유하는 고정 번호여야 한다.
resource "aws_eip" "scenelog" {
  instance = aws_instance.scenelog.id
  domain   = "vpc"
  tags     = { Name = "scenelog-eip" }
}

output "public_ip" {
  value = aws_eip.scenelog.public_ip
}

output "swagger_url" {
  value = "http://${aws_eip.scenelog.public_ip}:8080/swagger-ui/index.html"
}

output "ssh_command" {
  value = "ssh -i ~/.ssh/devmatch-key.pem ec2-user@${aws_eip.scenelog.public_ip}"
}
