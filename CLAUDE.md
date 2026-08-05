# Auto_Measure_luoji 项目开发行为规范

## 项目概述

Auto_Measure_luoji 是一款 Android 自动测量工具应用，基于 Kotlin + Jetpack Compose 构建。

---

## 一、功能模块目录规范

每个功能必须有独立的文件夹，采用 feature-based 分层架构：

```
app/src/main/java/com/example/auto_measure_luoji/
├── feature/                         # 功能模块根目录
│   ├── camera/                     # 相机拍摄功能
│   │   ├── ui/                     #   界面层（Composable）
│   │   ├── domain/                 #   业务逻辑层（UseCase）
│   │   └── data/                   #   数据层（Repository）
│   ├── measure/                    # 测量功能
│   │   ├── ui/
│   │   ├── domain/
│   │   └── data/
│   ├── gallery/                    # 图片管理功能
│   │   ├── ui/
│   │   ├── domain/
│   │   └── data/
│   ├── settings/                   # 设置功能
│   │   ├── ui/
│   │   ├── domain/
│   │   └── data/
│   └── common/                     # 功能间共享组件
│       ├── component/              #   通用 Composable 组件
│       ├── model/                  #   共享数据模型
│       └── util/                   #   通用工具函数
├── core/                            # 核心基础设施（不属于任何功能）
│   ├── di/                         # 依赖注入
│   ├── navigation/                 # 导航路由
│   ├── network/                    # 网络层
│   ├── database/                   # 本地数据库
│   └── util/                       # 全局工具类
└── ui/theme/                        # 主题配置（已有）
```

### 目录规范细则

1. **新增功能**：必须先在 `feature/` 下创建独立文件夹
2. **文件命名**：Kotlin 文件使用 PascalCase（如 `CameraScreen.kt`）
3. **包名规范**：包名与目录路径一致
4. **禁止跨功能直接引用**：功能间通信通过 `core/` 层或共享接口

---

## 二、代码审查流程（每次编码必须执行）

每次编写或修改代码后，必须按以下 5 步逐一检查：

### Step 1: 需求符合性检查
- [ ] 对照需求文档逐条确认实现是否完整
- [ ] 不得擅自改动需求，如有优化建议必须主动提出并等待确认
- [ ] 记录所有需求偏差（如有）

### Step 2: 逻辑正确性检查
- [ ] 边界条件处理（空值、零值、极值）
- [ ] 异常路径覆盖（网络失败、权限拒绝、存储满）
- [ ] 状态一致性（UI 状态与数据状态同步）

### Step 3: 语法正确性检查
- [ ] Kotlin 语法无误（类型安全、空安全）
- [ ] Compose 注解正确（@Composable, @Preview）
- [ ] 依赖注入正确（@Inject, @HiltViewModel）

### Step 4: 逻辑漏洞扫描
- [ ] 内存泄漏风险（Context 引用、回调持有）
- [ ] 生命周期安全（Activity/Fragment 销毁后操作）
- [ ] 线程安全（主线程阻塞、协程作用域）
- [ ] 相机硬件控制：绝对禁止启用电子防抖 (EIS)

### Step 5: 需求变更记录
- 如发现需求不合理或有更好的实现方案，**必须主动提出**，等待哥哥确认后才能实施
- 记录格式：`[建议] 原需求 X → 建议改为 Y，理由：Z`

---

## 三、Git 分支管理策略

### 分支结构

```
main                    ← 稳定主干，只接受从 develop 合并
├── develop             ← 开发集成分支
│   ├── feature/xxx     ← 新功能分支
│   ├── bugfix/xxx      ← Bug 修复分支
│   └── refactor/xxx    ← 重构分支
```

### 分支操作规范

| 操作 | 命令 | 说明 |
|------|------|------|
| 创建功能分支 | `git checkout -b feature/xxx develop` | 从 develop 创建 |
| 提交代码 | `git commit -m "feat: 描述"` | 遵循 Conventional Commits |
| 合并到 develop | 先提 PR/告知哥哥，确认后合并 | 必须经过确认 |
| 合并到 main | `git merge develop` | develop 稳定后合并 |

### 提交信息规范

```
feat:     新功能
fix:      修复 Bug
refactor: 重构（不改变外部行为）
docs:     文档更新
style:    代码格式调整（不影响逻辑）
test:     添加/修改测试
chore:    构建工具、依赖变更
perf:     性能优化
ci:       CI/CD 配置变更
```

示例：
```
feat: 实现相机预览界面
fix: 修复测量坐标偏移问题
refactor: 抽取公共 Composable 组件
docs: 更新 README 功能说明
```

---

## 四、文档同步规则

每次代码修改必须同步更新以下文档：

### 4.1 README.md
- 功能变更时更新"功能列表"章节
- 架构变更时更新"项目结构"章节
- 依赖变更时更新"技术栈"章节

### 4.2 CHANGELOG.md
- 每次提交必须在 CHANGELOG.md 中记录
- 格式：

```markdown
## [版本号] - YYYY-MM-DD
### Added
- 新增功能描述
### Changed
- 变更描述
### Fixed
- 修复描述
```

---

## 五、新功能开发流程

```
1. 哥哥提出需求
       ↓
2. 我确认需求理解，提出疑问或建议
       ↓
3. 从 develop 创建 feature/xxx 分支
       ↓
4. 在分支上实现功能
       ↓
5. 执行代码审查（5 步流程）
       ↓
6. 更新 README.md 和 CHANGELOG.md
       ↓
7. 向哥哥展示代码，等待确认
       ↓
8. 确认通过后合并到 develop
       ↓
9. develop 稳定后合并到 main
```

---

## 六、禁止事项

1. **禁止**直接在 main 分支上开发
2. **禁止**未经确认就合并功能分支
3. **禁止**擅自改动需求（有建议必须先提出）
4. **禁止**提交未通过代码审查的代码
5. **禁止**启用电子防抖 (EIS)
6. **禁止**在代码中硬编码密钥、密码等敏感信息
7. **禁止**跳过文档同步直接提交
