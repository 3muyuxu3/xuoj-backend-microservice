# 栩 OJ 后端（微服务版）

基于 Spring Cloud Alibaba 微服务架构的在线判题系统（Online Judge）后端。项目在单体 OJ 的基础上拆分为网关、用户服务、题目服务、判题服务四个可独立部署的服务，判题流程通过 RabbitMQ 异步解耦，服务间使用 OpenFeign 进行调用，注册中心为 Nacos。

## 技术栈

| 类别 | 技术 | 版本 |
| --- | --- | --- |
| 语言 / 构建 | Java / Maven | 1.8 / 3.x |
| 基础框架 | Spring Boot | 2.6.13 |
| 微服务 | Spring Cloud / Spring Cloud Alibaba | 2021.0.5 / 2021.0.5.0 |
| 网关 | Spring Cloud Gateway | - |
| 注册中心 | Nacos | 2.2.0 |
| 服务调用 | OpenFeign + Spring Cloud LoadBalancer | - |
| 流量防护 | Sentinel | - |
| 数据库 | MySQL + MyBatis-Plus | 8 / 3.5.2 |
| 缓存 / 会话 | Redis + Spring Session | 6 / - |
| 消息队列 | RabbitMQ | 3.12.6 |
| 接口文档 | Knife4j（Swagger） | - |
| 工具库 | Hutool、Gson、Apache Commons、EasyExcel、Lombok | - |

## 系统架构

### 模块划分

| 模块 | 说明 | 端口 |
| --- | --- | --- |
| xuoj-backend-gateway | 统一入口网关，路由转发、全局过滤、文档聚合 | 8101 |
| xuoj-backend-user-service | 用户服务，注册、登录、用户管理 | 8102 |
| xuoj-backend-question-service | 题目服务，题目管理、题目提交、发送判题消息 | 8103 |
| xuoj-backend-judge-service | 判题服务，消费判题消息、调用代码沙箱、得出判题结果 | 8104 |
| xuoj-backend-common | 公共模块，统一响应、错误码、异常处理、常量、工具类 | - |
| xuoj-backend-model | 数据模型模块，实体、DTO、VO、枚举、代码沙箱模型 | - |
| xuoj-backend-service-client | 服务调用模块，Feign 接口定义（user / question / judge） | - |

common、model、service-client 为公共依赖库，不打成可运行服务。

### 架构示意图

```
                          +---------------------+
                          |     前端 / 调用方    |
                          +----------+----------+
                                     |
                                     v
                          +---------------------+
                          |   gateway  (8101)   |
                          | 路由转发 / 全局过滤  |
                          +----------+----------+
                                     |  lb:// （Nacos 服务发现 + 负载均衡）
              +----------------------+----------------------+
              |                      |                      |
              v                      v                      v
    +------------------+  +---------------------+  +------------------+
    |  user-service    |  |  question-service   |  |  judge-service   |
    |      8102        |  |        8103         |  |       8104       |
    +--------+---------+  +----------+----------+  +--------+---------+
             |                       |                      ^
             |                       |  1. 提交题目后发送消息 |
             |                       +------> RabbitMQ -----+
             |                          code_exchange        |
             |                          / code_queue         |
             |                                               |
             |         2. OpenFeign 内部调用取提交/题目信息     |
             |<----------------------------------------------+
             |                                               |
             v                                               v
    +---------------------------------------------------------------+
    |  MySQL 8 (3306)   Redis 6 (6379)   RabbitMQ (5672)  Nacos (8848) |
    +---------------------------------------------------------------+
                                     |
                                     v  3. HTTP 调用判题
                          +---------------------+
                          |  远程代码沙箱 :8090  |  独立部署，不在本仓库内
                          +---------------------+
```

## 核心业务流程

### 用户与题目

- 用户：注册、登录、注销、获取当前登录用户、用户增删改查、分页查询、修改个人信息，管理员接口通过注解校验权限。
- 会话：Spring Session + Redis 集中存储，Cookie 有效期 30 天，多服务间共享登录态。
- 题目：题目的增删改查、分页查询、编辑；题目包含判题用例（judgeCase，JSON 数组）和判题配置（judgeConfig，JSON 对象）。

### 判题流程（异步）

1. 用户在题目服务提交代码：`POST /api/question/question_submit/do`。
2. 题目服务校验编程语言并保存提交记录（状态为 0 待判题），随后向 RabbitMQ 的 `code_exchange` 交换机以路由键 `my_routingKey` 发送提交 id。
3. 判题服务监听 `code_queue` 队列（手动 ACK），收到消息后调用判题逻辑：
   - 通过 OpenFeign 从题目服务获取提交信息与题目信息；
   - 校验提交状态，将状态置为 1 判题中（防止重复判题）；
   - 通过代码沙箱工厂创建沙箱实例（由配置 `codesandbox.type` 决定），经代理类增强后执行代码，拿到各测试用例的输出；
   - 由判题管理器按编程语言选择判题策略（默认策略 / Java 策略）比对输出，得出判题结果；
   - 更新提交记录的状态（2 成功 / 3 失败）与判题信息；处理失败时消息 nack。
4. 前端轮询或查询提交记录获取判题结果。

相关枚举取值：

- 提交状态：0 待判题、1 判题中、2 成功、3 失败。
- 支持语言：java、cpp、go。

### 网关

- 路由规则（按路径前缀转发）：
  - `/api/user/**` 转发到 user-service
  - `/api/question/**` 转发到 question-service
  - `/api/judge/**` 转发到 judge-service
- 全局过滤器拦截 `/**/inner/**` 路径（内部接口不允许外部访问，返回 403）。
- 提供跨域配置，并使用 Knife4j 聚合各服务接口文档。

### 服务间调用

- judge-service 通过 `QuestionFeignClient` 调用题目服务的内部接口获取题目与提交信息、更新提交状态。
- question-service 通过 `UserFeignClient` 获取用户信息。
- `JudgeFeignClient` 定义了同步判题的内部接口（当前主流程使用消息队列异步判题）。

## 端口约定

| 组件 | 端口 | 说明 |
| --- | --- | --- |
| gateway | 8101 | 服务统一入口 |
| user-service | 8102 | 上下文路径 /api/user |
| question-service | 8103 | 上下文路径 /api/question |
| judge-service | 8104 | 上下文路径 /api/judge |
| MySQL | 3306 | 账号 root / 123456 |
| Redis | 6379 | 会话存储使用 5 号库 |
| RabbitMQ | 5672 / 15672 | 账号 guest / guest，15672 为管理面板 |
| Nacos | 8848 | 单机模式，控制台 /nacos |
| 远程代码沙箱 | 8090 | 独立部署，本仓库不包含 |

## 快速开始

### 环境要求

- JDK 1.8 及以上
- Maven 3.6 及以上
- Docker 与 Docker Compose（用于启动中间件，可选）

### 1. 启动中间件

项目提供 `docker-compose.env.yml`，一键启动 MySQL、Redis、RabbitMQ、Nacos：

```bash
docker compose -f docker-compose.env.yml up -d
```

说明：

- MySQL 启动时会自动执行 `mysql-init/create_table.sql`，完成建库（xuoj）与建表（user、question、question_submit）。
- Nacos 以 standalone 单机模式启动，控制台地址为 `http://localhost:8848/nacos`。
- RabbitMQ 管理面板地址为 `http://localhost:15672`。

### 2. 构建项目

在项目根目录执行：

```bash
mvn clean package -DskipTests
```

### 3. 启动服务

依次启动以下四个服务（注册到 Nacos 后即可互相发现）：

- `com.xu.xuojbackendgateway.XuojBackendGatewayApplication`
- `com.xu.xuojbackenduserservice.XuojBackendUserServiceApplication`
- `com.xu.xuojbackendquestionservice.XuojBackendQuestionServiceApplication`
- `com.xu.xuojbackendjudgeservice.XuojBackendJudgeServiceApplication`

默认激活 dev 配置，连接本机（localhost）的 MySQL、Redis、RabbitMQ、Nacos。若中间件部署在其他机器，请修改各服务 `application.yml` 中的连接地址。

判题服务启动时会自动声明判题使用的交换机（`code_exchange`）与队列（`code_queue`）并完成绑定（`InitRabbitMqBean`）。

### 4. Docker 部署（可选）

先构建 jar 包，再通过编排文件启动业务服务：

```bash
mvn clean package -DskipTests
docker compose -f docker-compose.env.yml up -d
docker compose -f docker-compose.service.yml up -d --build
```

四个服务的 Dockerfile 基于 `openjdk:8-jdk-alpine`，以 `prod` 配置启动（`--spring.profiles.active=prod`），prod 配置中通过容器名（mysql、redis、nacos、rabbitmq）访问中间件，各服务与中间件同在 `mynetwork` 网络中。

## 配置说明

| 配置项 | 位置 | 说明 |
| --- | --- | --- |
| 数据库 / Redis / Nacos / RabbitMQ 地址 | 各服务 `application.yml`（dev）、`application-prod.yml`（prod） | 默认 dev 为 localhost，prod 为容器名 |
| `codesandbox.type` | question-service、judge-service | 代码沙箱类型：example / remote / thirdParty，默认 remote |
| 远程沙箱地址 | judge-service `RemoteCodeSandbox` | 默认 `http://localhost:8090/executeCode`，跨机器部署时需修改 |
| 沙箱鉴权 | judge-service `RemoteCodeSandbox` | 请求头 `auth: secretKey`，生产环境应替换为更安全的密钥 |
| MyBatis-Plus | 各服务 `application.yml` | 关闭下划线转驼峰，逻辑删除字段 isDelete |
| 接口文档 | gateway 和各服务 `application.yml` | Knife4j 开启，网关聚合模式 |

接口文档入口：`http://localhost:8101/doc.html`（网关聚合各服务文档）。

## 目录结构

```
xuoj-backend-microservice
├── docker-compose.env.yml          # 中间件编排：MySQL / Redis / RabbitMQ / Nacos
├── docker-compose.service.yml      # 业务服务编排：gateway / user / question / judge
├── mysql-init/
│   └── create_table.sql            # 数据库初始化脚本
├── pom.xml                         # 父 POM，统一依赖与版本管理
├── xuoj-backend-common/            # 公共模块：统一响应、错误码、异常、常量、工具类
├── xuoj-backend-model/             # 数据模型模块：实体、DTO、VO、枚举
├── xuoj-backend-service-client/    # 服务调用模块：Feign 客户端接口
├── xuoj-backend-gateway/           # 网关服务
├── xuoj-backend-user-service/      # 用户服务
├── xuoj-backend-question-service/  # 题目服务
└── xuoj-backend-judge-service/     # 判题服务
```

## 设计要点

- 工厂模式：`CodeSandboxFactory` 根据类型字符串创建对应的代码沙箱实例（example / remote / thirdParty）。
- 代理模式：`CodeSandboxProxy` 对代码沙箱调用做统一增强（如日志）。
- 策略模式：`JudgeManager` 按编程语言选择判题策略（`DefaultJudgeStrategy` / `JavaLanguageJudgeStrategy`）。
- 异步解耦：提交与判题通过 RabbitMQ 解耦，判题服务手动 ACK，处理失败时 nack，避免消息丢失。
- 内部接口隔离：Feign 内部接口统一放在 `/inner` 路径下，网关的全局过滤器禁止外部访问。
- 逻辑删除：数据库删除字段 isDelete，由 MyBatis-Plus 统一处理。

## 注意事项

- 本仓库不包含代码沙箱服务，判题服务默认调用 `http://localhost:8090` 的远程沙箱，未部署沙箱时判题会失败。
- 数据库密码、RabbitMQ 账号、沙箱密钥等均为开发环境默认值，生产环境务必修改。
- 各服务默认使用 Redis 的 5 号库存放会话，请确保 Redis 可用。
