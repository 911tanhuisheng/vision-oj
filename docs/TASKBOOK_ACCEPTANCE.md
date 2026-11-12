# 任务书验收对照与复测记录

以 `F:/毕业论文/20231084022 谭惠升 2023级智能科学与技术1班/2 20231084022 谭惠升 2023级智能科学与技术1班 南宁学院本科毕业论文（设计）任务书.docx` 为最终任务书。状态只依据当前源码、自动测试和保存的真实实验结果；功能实现不等于性能指标达标。

## 功能对照

| 任务书模块 | 当前实现与证据 | 后续验收 |
| --- | --- | --- |
| 用户、题目及权限管理 | Spring Boot `QuestionController`、`QuestionServiceImpl`；Vue 题库和管理页；题型与识别模型组合双端校验 | 用教师和学生账号各走一遍权限与编辑流程 |
| 视觉辅助命题 | `vision-ai/app/main.py` 的 YOLO、EasyOCR、属性分析；管理端可修改标准输出 | 保存教师复核前后样例与错误标注案例 |
| 公开与隐藏图像用例 | `QuestionServiceImpl.validQuestion` 要求至少一张公开图和一张不同的隐藏图；`QuestionServiceImpl` 只向学生返回公开用例 | 抓取学生详情响应，确认不包含隐藏图片和答案 |
| 五类图像题判题 | `ObjectCountJudgeStrategy`、`ImageOutputJudgeStrategy`；`QuestionPreflightService` 现对五类参考程序均在沙箱实跑 | 收集正确、错误、格式错误和容差边界的逐例结果 |
| Docker 隔离执行 | `Sandbox` Python 容器使用 `network=none`、非 root、只读根目录、资源限制和临时文件清理 | 在 Ubuntu 判题机实测网络、超时、内存、清理和结果回写 |
| 交互与结果管理 | 管理端题目编辑、学生端做题与提交、状态轮询和错误详情 | 浏览器逐项截图并记录提交编号与终态 |

## 任务书量化指标

| 指标 | 要求 | 当前证据与结论 |
| --- | --- | --- |
| 安全帽模型 mAP@0.5 | ≥ 0.80 | **未达标**。独立测试集 883 张，当前权重 0.6763；见 `evidence/hard_hat_test.json`。`head` 为 0.9756，`helmet` 为 0.9857，`person` 为 0.0675；训练标注实例分别为 4874、14884、473。不能删去 `person` 类后声称三类模型达标。 |
| OCR 文字准确率 | ≥ 0.90 | **待测**。需要独立人工转写图片清单。`vision-ai/evaluate_ocr.py` 可按字符编辑距离输出逐图和总体结果；空清单不会产出数字。 |
| 判题与人工标注一致率 | ≥ 0.95 | **待测**。需由人工独立标注正确、错误及边界案例，再与系统判定比较；单元测试通过率不能代替此指标。 |
| 提交到结果响应时间 | ≤ 5 秒 | **待测**。计时必须从接收提交到状态变为成功或失败，不能只计 API 受理时间。图像模型冷启动、Python 沙箱启动与推理均须纳入。 |
| 20 名学生并发提交 | 稳定运行 | **待测**。`benchmark_submissions.py` 要求 20 个不同测试账号，记录每次提交终态及耗时；当前没有该项实测报告。 |
| 普通 PC 可运行 | CPU 或低端 GPU | **待测**。当前模型评估设备为 RTX 4050 Laptop GPU；还需单独记录 CPU 或普通 PC 环境的启动、推理与判题情况。 |

## 复测命令与数据规则

1. 目标检测：在 `vision-ai` 目录运行 `.venv/Scripts/python.exe evaluate_hard_hat.py --device 0 --batch 8`。脚本固定使用独立 `test` 划分，保存模型与数据配置 SHA-256、类别指标和运行环境到 `docs/evidence/hard_hat_test.json`。改进模型时只用训练集和验证集选参数，定稿后才在独立测试集报告一次最终结果。
   可用 `.venv/Scripts/python.exe finetune_hard_hat.py --epochs 30 --batch 4 --class-weight-power 0.5` 尝试对少样本 `person` 类加权。实验权重保存在 `vision-ai/runs`，不会覆盖生产模型；先比较验证集，再决定是否进行最终测试。
   2026-10-30 已完成 `--epochs 1 --batch 2 --class-weight-power 0.5` 的配置试跑，验证集 mAP50 为 0.66158，`person` 约 0.0396。**这是训练配置检查，不是改进成功，也不是独立测试结果**；未替换生产权重。训练日志保留在本地 `vision-ai/runs/hard-hat-weight-smoke`，该目录不纳入源码交付。
2. OCR：准备**人工转写**的 UTF-8 CSV，表头为 `image,expected`，每行对应一张独立测试图片；运行 `.venv/Scripts/python.exe evaluate_ocr.py --manifest <CSV路径> --gpu`。记录样本来源、规模、逐图识别文本和总字符准确率。不要把 AI 生成的转写当作人工真值。
3. 并发：在测试环境准备 20 个不同学生账号的临时令牌、同一道可提交的测试题与正确 Python 代码；运行 `python docs/benchmark_submissions.py --base-url http://127.0.0.1:8888/api --tokens <令牌文件> --question-id <题号> --code-file <代码文件> --confirm-run`。令牌文件留在仓库外，测试后作废。脚本会创建真实提交，不应对正式题库运行。
4. 判题一致率：固定人工标注集和判定规则，保存 `题型、输入、预期输出、系统输出、预期结论、实际结论、错误类型`。分别统计整体及五类题，不能用“系统自己生成的标准答案”再验证系统自身。

## 当前优先工作

1. 核查 `person` 类的少样本标注、漏标和数据划分，利用训练集/验证集改进模型；保留当前独立测试集作为最终比较基准。
2. 建立 OCR 人工转写集和判题人工标注集，补齐 90% 与 95% 的原始证据。
3. 在 Ubuntu 运行 Docker 沙箱与完整链路测试，再做 20 人并发和 5 秒响应测试；不能以 Windows 单元测试替代部署测试。
4. 若指标确实无法达到，与指导老师讨论任务书调整；论文必须如实报告已测结果和限制。
