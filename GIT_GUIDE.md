# 本地 Git 恢复指南

已经建立本地 Git 仓库，初始提交保存当前最小付款闭环代码。未配置远程仓库，也未上传代码。

## 查看改动

在项目根目录执行：

```powershell
git status
git diff
git diff -- src/main/java/com/stripe/sample/Server.java
git log --oneline -10
```

## 恢复误改的文件

先查看差异，确认这些未提交的修改都不需要，再执行：

```powershell
git restore --source=HEAD --staged --worktree -- src/main/java/com/stripe/sample/Server.java
```

这会把这个文件的磁盘内容和暂存内容恢复到最近一次提交，覆盖该文件尚未提交的修改。IDE 如果还保留未保存的编辑内容，要重新从磁盘加载文件，避免再次保存旧内容。

VS Code 也可以在左侧“源代码管理”中打开文件差异，确认后对该文件选择“放弃更改”。

## 保存下一个恢复点

每完成一个可以保留的阶段，先检查再提交：

```powershell
git status
git diff
git add src public pom.xml README.md PAYMENT_CHECKLIST.md GIT_GUIDE.md .gitignore .env.example
git diff --cached
git commit -m "说明这次改了什么"
```

`git add` 只是暂存，`git commit` 才会创建历史恢复点；`.env.example` 只允许放占位符。

## 找回更早版本

先查看某个提交中的文件，不修改当前文件：

```powershell
git log --oneline -- src/main/java/com/stripe/sample/Server.java
git show <提交号>:src/main/java/com/stripe/sample/Server.java
```

确认要恢复后，把 `<提交号>` 替换为真实值：

```powershell
git restore --source=<提交号> -- src/main/java/com/stripe/sample/Server.java
git diff
```

确认结果后再次提交即可，原来的提交历史仍然保留。

## 不纳入 Git 的内容

构建产物、H2 数据库、真实环境配置、本地 IDE 设置、日志和原始 ZIP 均已忽略。源码、页面、测试、配置占位示例与付款进度清单会纳入版本控制。

Git 提交不备份被忽略的数据库或真实密钥；当前仓库也只是本地历史，不是异地备份。
