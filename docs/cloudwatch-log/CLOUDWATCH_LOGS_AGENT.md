# CloudWatch Logs Agent - Documentation

## Mục lục
1. [Tổng quan](#tổng-quan)
2. [So sánh các loại Agent](#so-sánh-các-loại-agent)
3. [Kiến trúc hoạt động](#kiến-trúc-hoạt-động)
4. [Cấu hình Agent](#cấu-hình-agent)
5. [Use Cases](#use-cases)
6. [Tài liệu tham khảo](#tài-liệu-tham-khảo)

---

## Tổng quan

CloudWatch Agent là phần mềm thu thập metrics, logs và traces từ EC2 instances, on-premises servers, và containerized applications. Agent cho phép monitor infrastructure toàn diện hơn so với basic monitoring mặc định.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        CloudWatch Agent                                  │
│                                                                          │
│   ┌───────────────┐   ┌───────────────┐   ┌───────────────┐             │
│   │   Metrics     │   │     Logs      │   │    Traces     │             │
│   │  (CPU, RAM,   │   │ (Application, │   │ (OpenTelemetry│             │
│   │   Disk...)    │   │  System logs) │   │   X-Ray SDK)  │             │
│   └───────┬───────┘   └───────┬───────┘   └───────┬───────┘             │
│           │                   │                   │                      │
│           └─────────────────────────────────────────────────────────────►│ AWS
│                                                                          │
│                   CloudWatch   │   CloudWatch Logs   │   X-Ray           │
└─────────────────────────────────────────────────────────────────────────┘
```

### Các thành phần chính

| Thành phần | Mô tả |
|------------|-------|
| **Unified CloudWatch Agent** | Agent mới (khuyến nghị), hỗ trợ cả metrics + logs + traces |
| **CloudWatch Logs Agent** | Agent cũ (deprecated), chỉ hỗ trợ logs |
| **Configuration File** | JSON file định nghĩa metrics/logs cần thu thập |
| **amazon-cloudwatch-agent-ctl** | CLI tool để quản lý agent |

---

## So sánh các loại Agent

### Unified CloudWatch Agent vs CloudWatch Logs Agent (Deprecated)

| Tính năng | Unified Agent ✅ | Logs Agent (Deprecated) ❌ |
|-----------|------------------|---------------------------|
| **Metrics collection** | ✅ Có | ❌ Không |
| **Logs collection** | ✅ Có | ✅ Có |
| **Traces collection** | ✅ Có (v1.300025.0+) | ❌ Không |
| **IMDSv2 support** | ✅ Có | ❌ Không |
| **Language** | Go (memory efficient) | Python |
| **Configuration** | JSON file | INI file |
| **StatsD/collectd** | ✅ Có | ❌ Không |
| **Windows Event Log** | ✅ Có | ❌ Không |

> ⚠️ **AWS khuyến nghị sử dụng Unified CloudWatch Agent** cho tất cả deployments mới.

### Unified Agent vs Application-level Logging

| Approach | Unified Agent | App-level (Logback Appender) |
|----------|--------------|------------------------------|
| **Setup** | Cài agent trên server | Cấu hình trong application |
| **Scope** | System + application logs | Chỉ application logs |
| **System metrics** | ✅ CPU, RAM, Disk | ❌ Không |
| **Dependency** | Daemon process | Library dependency |
| **Use case** | EC2/On-premise servers | Containers, Lambda |

---

## Kiến trúc hoạt động

### Daemon là gì?

**Daemon** = chương trình chạy ngầm (background process), không cần user tương tác.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Khi server khởi động                                                    │
│                                                                          │
│   Foreground (user thấy)              Background (daemon - chạy ngầm)   │
│                                                                          │
│   $ java -jar app.jar                 ✓ amazon-cloudwatch-agent         │
│   $ vim file.txt                      ✓ nginx                           │
│   $ ls -la                            ✓ sshd                            │
│                                       ✓ dockerd                          │
│   (cần terminal)                      (tự chạy, không cần terminal)     │
└─────────────────────────────────────────────────────────────────────────┘
```

| Đặc điểm | Mô tả |
|----------|-------|
| **Chạy ngầm** | Không chiếm terminal, user không thấy output |
| **Tự khởi động** | Bật khi server boot, không cần user chạy lệnh |
| **Chạy liên tục** | 24/7, tự restart nếu crash |
| **Không tương tác** | Không cần nhập input từ keyboard |

### Cách Agent thu thập data

#### 1. Thu thập Metrics (đọc từ OS)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Server (Linux)                                                          │
│                                                                          │
│   /proc/stat         ◄────┐                                             │
│   /proc/meminfo      ◄────┼──── Agent ĐỌC các file hệ thống             │
│   /proc/diskstats    ◄────┘     mỗi X giây (vd: 60s)                    │
│                                                                          │
│   Ví dụ: cat /proc/meminfo                                              │
│   MemTotal:       16384000 kB                                           │
│   MemFree:         4096000 kB   ──► Agent tính: 75% RAM used            │
└─────────────────────────────────────────────────────────────────────────┘
```

**Cơ chế:** Agent chạy như daemon, định kỳ đọc files `/proc/*` để lấy CPU, RAM, Disk, Network.

#### 2. Thu thập Logs (tail file)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Server                                                                  │
│                                                                          │
│   /var/log/app.log                                                      │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │ 2026-01-18 10:00:01 INFO  User login                            │   │
│   │ 2026-01-18 10:00:02 ERROR Payment failed  ◄── Agent THEO DÕI    │   │
│   │ 2026-01-18 10:00:03 INFO  Order created       file thay đổi     │   │
│   │ ... (new lines)                               (như tail -f)     │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│   Agent:                                                                 │
│   - Ghi nhớ vị trí đọc cuối (offset)                                    │
│   - Khi file có dòng mới → đọc và gửi đi                                │
└─────────────────────────────────────────────────────────────────────────┘
```

**Cơ chế:** Agent hoạt động như `tail -f`, theo dõi file changes và gửi dòng mới.

#### 3. Thu thập Traces (nhận từ app)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Server                                                                  │
│                                                                          │
│   ┌─────────────────┐                                                   │
│   │   Application   │                                                   │
│   │   (Java app)    │                                                   │
│   │                 │                                                   │
│   │  OTEL SDK gửi   │────── HTTP POST ──────►  Agent lắng nghe         │
│   │  trace data     │       localhost:4317      port 4317               │
│   └─────────────────┘                                                   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

**Cơ chế:** Khác với metrics/logs, agent **không chủ động đọc**. App chủ động **gửi tới** agent qua HTTP/gRPC.

#### Tóm tắt cơ chế thu thập

| Loại | Agent làm gì | Nguồn data |
|------|--------------|------------|
| **Metrics** | Đọc file `/proc/*` định kỳ | OS kernel expose qua filesystem |
| **Logs** | Tail file log | Application viết ra file |
| **Traces** | Lắng nghe port, nhận HTTP | App SDK gửi tới agent |

### Flow tổng quan

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            EC2 Instance / On-Premises                     │
│                                                                           │
│  ┌─────────────────┐    ┌─────────────────────────────────────────────┐  │
│  │   Application   │───►│  Log Files                                   │  │
│  │   (Java, Node)  │    │  /var/log/app.log                           │  │
│  └─────────────────┘    │  /var/log/messages                          │  │
│                         │  /var/log/nginx/access.log                   │  │
│                         └─────────────────┬───────────────────────────┘  │
│                                           │                               │
│                                           ▼                               │
│                         ┌─────────────────────────────────────────────┐  │
│                         │        CloudWatch Agent (Daemon)             │  │
│                         │                                              │  │
│                         │  ┌───────────┐  ┌───────────┐  ┌──────────┐ │  │
│                         │  │  Metrics  │  │   Logs    │  │  Traces  │ │  │
│                         │  │ Collector │  │ Collector │  │Collector │ │  │
│                         │  └─────┬─────┘  └─────┬─────┘  └────┬─────┘ │  │
│                         └────────┼──────────────┼─────────────┼───────┘  │
│                                  │              │             │          │
└──────────────────────────────────┼──────────────┼─────────────┼──────────┘
                                   │              │             │
                                   ▼              ▼             ▼
                            ┌─────────────────────────────────────────┐
                            │              AWS Cloud                   │
                            │                                          │
                            │  CloudWatch    CloudWatch     X-Ray      │
                            │   Metrics       Logs                     │
                            └─────────────────────────────────────────┘
```

### Agent Configuration Structure

```json
{
  "agent": {
    // Global settings (collection interval, region, debug)
  },
  "metrics": {
    // System metrics: CPU, Memory, Disk, Network
    // Custom metrics: StatsD, collectd
  },
  "logs": {
    // Log files to collect
    // Windows Event Logs (Windows only)
  },
  "traces": {
    // OpenTelemetry/X-Ray trace sources
  }
}
```

---

## Cấu hình Agent

### Cấu trúc Configuration File

```json
{
  "agent": {
    "metrics_collection_interval": 60,
    "region": "ap-southeast-1",
    "logfile": "/opt/aws/amazon-cloudwatch-agent/logs/amazon-cloudwatch-agent.log",
    "debug": false
  },
  "metrics": {
    "namespace": "MyApp",
    "metrics_collected": {
      "cpu": {
        "measurement": ["usage_active", "usage_idle"],
        "totalcpu": true
      },
      "mem": {
        "measurement": ["used_percent"]
      },
      "disk": {
        "measurement": ["used_percent"],
        "resources": ["/", "/data"]
      }
    }
  },
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/app/*.log",
            "log_group_name": "/app/production",
            "log_stream_name": "{instance_id}",
            "timestamp_format": "%Y-%m-%d %H:%M:%S"
          },
          {
            "file_path": "/var/log/messages",
            "log_group_name": "/system/messages",
            "log_stream_name": "{hostname}"
          }
        ]
      }
    }
  }
}
```

### Các Field quan trọng

#### Agent Section

| Field | Mô tả | Default |
|-------|-------|---------|
| `metrics_collection_interval` | Interval thu thập metrics (seconds) | 60 |
| `region` | AWS region gửi data | EC2 instance region |
| `debug` | Enable debug logging | false |
| `run_as_user` | User để chạy agent (Linux) | root |

#### Logs Section

| Field | Mô tả | Ví dụ |
|-------|-------|-------|
| `file_path` | Path tới log file (hỗ trợ wildcard) | `/var/log/app/*.log` |
| `log_group_name` | Tên Log Group đích | `/app/production` |
| `log_stream_name` | Tên Log Stream (hỗ trợ variables) | `{instance_id}` |
| `timestamp_format` | Format của timestamp trong log | `%Y-%m-%d %H:%M:%S` |
| `multi_line_start_pattern` | Pattern cho multi-line logs | `{datetime_format}` |
| `encoding` | Encoding của file | `utf-8` |

#### Log Stream Name Variables

| Variable | Giá trị |
|----------|---------|
| `{instance_id}` | EC2 Instance ID (i-0123456789abcdef) |
| `{hostname}` | Hostname của server |
| `{ip_address}` | Private IP address |

### Multiple Configuration Files

Agent hỗ trợ merge nhiều config files:

```bash
# Start với config chính
amazon-cloudwatch-agent-ctl -a fetch-config -c file:/tmp/base.json

# Append thêm config
amazon-cloudwatch-agent-ctl -a append-config -c file:/tmp/app.json
```

Ví dụ: `infrastructure.json` cho system metrics, `app.json` cho application logs.

---

## Use Cases

### 1. Monitor EC2 Instances với System Metrics + Application Logs

**Scenario:** Cần monitor CPU, Memory, Disk usage đồng thời với application logs.

**Real-world example:** E-commerce platform chạy trên EC2 fleet. DevOps team cần theo dõi resource utilization để auto-scale, đồng thời collect application logs để debug khi có issues. Một agent xử lý cả hai thay vì chạy nhiều daemon processes.

### 2. Legacy Applications trên On-Premises Servers

**Scenario:** Applications không thể modify code để sử dụng SDK/library logging.

**Real-world example:** Enterprise banking system chạy Java applications trên physical servers. Code base đã 10+ năm, không thể thêm dependencies mới. CloudWatch Agent đọc trực tiếp log files mà không cần modify application code.

### 3. Hybrid Cloud Environment

**Scenario:** Mix EC2 instances và on-premises servers cần centralized logging.

**Real-world example:** Retail company với e-commerce trên AWS và inventory system on-premises. CloudWatch Agent cài trên cả hai environments, gửi logs về cùng một Log Group để có unified view.

### 4. Collect Windows Event Logs

**Scenario:** Windows Server applications cần monitor Event Logs.

**Real-world example:** .NET applications trên Windows Server. Agent collect System, Application, Security event logs và gửi về CloudWatch Logs để detect security issues hoặc application crashes.

---

## Installation Flow (Reference Only)

```
┌─────────────────────────────────────────────────────────────────────────┐
│  1. Create IAM Role                                                      │
│     └── Attach CloudWatchAgentServerPolicy                              │
│                              │                                           │
│                              ▼                                           │
│  2. Download Agent Package                                               │
│     └── wget https://s3.amazonaws.com/.../amazon-cloudwatch-agent.rpm   │
│                              │                                           │
│                              ▼                                           │
│  3. Install Agent                                                        │
│     └── rpm -U ./amazon-cloudwatch-agent.rpm (hoặc dpkg, msi)           │
│                              │                                           │
│                              ▼                                           │
│  4. Create Configuration File                                            │
│     └── Wizard: amazon-cloudwatch-agent-config-wizard                   │
│     └── Manual: /opt/aws/amazon-cloudwatch-agent/etc/config.json        │
│                              │                                           │
│                              ▼                                           │
│  5. Start Agent                                                          │
│     └── amazon-cloudwatch-agent-ctl -a fetch-config -c file:config.json │
└─────────────────────────────────────────────────────────────────────────┘
```

### Agent Management Commands

| Action | Command |
|--------|---------|
| **Start** | `amazon-cloudwatch-agent-ctl -a fetch-config -c file:config.json -s` |
| **Stop** | `amazon-cloudwatch-agent-ctl -a stop` |
| **Status** | `amazon-cloudwatch-agent-ctl -a status` |
| **Append config** | `amazon-cloudwatch-agent-ctl -a append-config -c file:extra.json -s` |

---

## Khi nào sử dụng Agent vs Application-level Logging?

| Scenario | Recommendation |
|----------|----------------|
| EC2 với system + app logs | ✅ **Unified Agent** |
| Containers (ECS, EKS) | ❌ Dùng Fluent Bit / FireLens |
| Lambda | ❌ Built-in CloudWatch integration |
| Legacy apps (không modify code) | ✅ **Unified Agent** |
| Spring Boot với đầy đủ control | ✅ **Logback Appender** (đã implement) |
| Cần system metrics (CPU, RAM) | ✅ **Unified Agent** |
| On-premises servers | ✅ **Unified Agent** |

---

## Tài liệu tham khảo

| Chủ đề | Link |
|--------|------|
| **CloudWatch Agent Overview** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/Install-CloudWatch-Agent.html |
| **Agent Configuration File** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Agent-Configuration-File-Details.html |
| **Agent Configuration Wizard** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/create-cloudwatch-agent-configuration-file-wizard.html |
| **Metrics Collected by Agent** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/metrics-collected-by-CloudWatch-agent.html |
| **Troubleshooting Agent** | https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/troubleshooting-CloudWatch-Agent.html |
| **Agent GitHub (Open Source)** | https://github.com/aws/amazon-cloudwatch-agent |

---

*Ngày tạo: 2026-01-18*
*Project: realworld-exam*
