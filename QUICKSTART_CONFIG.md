# 快速开始 - 配置功能

## 5分钟快速上手

### 步骤 1: 创建配置文件

在项目根目录创建 `classrelation.properties`:

```properties
# 只分析 model 和 service 包
package.filters=org.example.model,org.example.service

# 只显示边数最多的3个连通分量
graph.max.components.to.render=3

# 设置默认样例代码路径（可选）
sample.code.path=/home/linux_zeng/projects/classRelationTestCode/src/main/java/org/example
```

### 步骤 2: 运行程序

**方式 A: 使用配置的默认路径**
```bash
java -jar classRelation.jar
```

**方式 B: 指定项目路径（覆盖配置）**
```bash
java -jar classRelation.jar /path/to/your/project
```

**方式 C: 临时修改包过滤**
```bash
java -jar classRelation.jar --package com.myapp.controller
```

### 步骤 3: 查看结果

生成的文件:
- `project-name.html` - 交互式关系图（只显示前3个连通分量）
- `project-name.md` - Markdown 文档报告

## 常用场景

### 场景 1: 大型项目聚焦核心模块

```properties
# 只关注核心业务包
package.filters=com.myapp.domain,com.myapp.service

# 只显示最核心的5个关系图
graph.max.components.to.render=5

# 严格模式：只看包内部的关系
package.strict.mode=true
```

### 场景 2: 微服务项目分析单个服务

```properties
# 分析特定服务的包
package.filters=com.myapp.orderservice.**

# 显示所有连通分量
graph.max.components.to.render=0

# OR 模式：包含与外部的交互
package.strict.mode=false
```

### 场景 3: 团队协作统一配置

将 `classrelation.properties` 提交到 Git:

```properties
# 团队统一的分析范围
package.filters=com.company.core.model,com.company.core.service
package.strict.mode=false
graph.max.components.to.render=10

# 开发环境默认路径
sample.code.path=./samples/default-project
```

团队成员只需运行:
```bash
java -jar classRelation.jar
```

### 场景 4: CI/CD 集成

```bash
# 在 CI 脚本中使用自定义配置
java -Dconfig.path=./ci/classrelation.properties \
     -jar classRelation.jar \
     --strict
```

## 配置项速查表

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `package.filters` | 字符串列表 | 空 | 包过滤规则，逗号分隔 |
| `package.strict.mode` | 布尔值 | false | 严格模式（AND vs OR） |
| `graph.max.components.to.render` | 整数 | 0 | 最大渲染的连通分量数（0=全部） |
| `sample.code.path` | 字符串 | 空 | 默认样例代码路径 |

## 包模式示例

```properties
# 精确匹配一个包
package.filters=com.example.model

# 匹配一层子包（model, dto, vo 等）
package.filters=com.example.*

# 匹配所有子包（递归）
package.filters=com.example.**

# 混合使用
package.filters=com.example.model,com.example.service,com.example.dto.*
```

## 故障排查

### 问题: 配置没有生效

**检查**:
```bash
# 查看控制台输出，应该看到:
# "Loaded configuration from: /path/to/classrelation.properties"

# 如果看到:
# "No configuration file found, using defaults"
# 说明配置文件不在搜索路径中
```

**解决**:
1. 确认文件名是 `classrelation.properties`（不是 .txt 或其他）
2. 放在当前目录或指定 `-Dconfig.path`
3. 检查文件编码应为 UTF-8

### 问题: 连通分量数量不对

**原因**: 设置为 0 会渲染所有分量

**解决**:
```properties
# 明确指定要显示的数量
graph.max.components.to.render=5
```

### 问题: 包过滤太严格，什么都没显示

**检查**:
```properties
# 尝试放宽过滤
package.filters=com.example.**  # 使用 ** 匹配所有子包
package.strict.mode=false       # 使用 OR 模式
```

## 进阶技巧

### 技巧 1: 多层级配置

```bash
# 全局配置
export JAVA_OPTS="-Dconfig.path=/etc/classrelation/global.properties"

# 项目级配置（覆盖全局）
java $JAVA_OPTS -jar classRelation.jar --package com.local.pkg
```

### 技巧 2: 动态生成配置

```bash
# 根据环境变量生成配置
cat > classrelation.properties << EOF
package.filters=${TARGET_PACKAGES}
graph.max.components.to.render=${MAX_COMPONENTS}
sample.code.path=${SAMPLE_PATH}
EOF

java -jar classRelation.jar
```

### 技巧 3: 对比不同配置的效果

```bash
# 第一次：宽松配置
java -jar classRelation.jar --package com.app.**
mv app.html app-all.html

# 第二次：严格配置
java -jar classRelation.jar --package com.app.model,com.app.service --strict
mv app-filtered.html app-core.html

# 对比两个 HTML 文件
```

## 下一步

- 阅读 [CONFIGURATION.md](CONFIGURATION.md) 了解完整配置选项
- 阅读 [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) 了解技术实现细节
- 查看示例配置文件: `src/main/resources/classrelation.properties`
