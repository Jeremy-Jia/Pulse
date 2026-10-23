# Pulse 1.9.0 真机验证

2026-10-23，hongkong / M610BB，OS4.0.24.0.XFRCNXM，Android 17，临时 KernelSU。使用与 1.8 相同的开发证书覆盖安装，保留应用数据与原有功能。固件/内核以各 JSON 实测记录为准。

## 完整签名对照

通过实际安装的 ResearchService 在 CPU 6 执行，每路径 512 次、3 轮，同轮的密钥、消息和签名随机输入相同；轮换顺序。全部 12 个样本有效，72 次 KAT case 执行通过，6144 个正式签名逐个验签通过，同轮跨路径签名字节摘要相同。各运行前另有非计分热身签名。

| 路径 | 签名次数/秒中位数 | 相对标量 | 采样均频 MHz |
| --- | ---: | ---: | ---: |
| scalar | 2182.7 | 1.000x | 2880 |
| neon | 3092.2 | 1.417x | 2880 |
| sve | 3237.4 | 1.483x | 2880 |
| sve2 | 3211.3 | 1.471x | 2880 |

SVE 普通向量为 128 bit，SME streaming 长度为 512 bit；单独进程通过 SET_VL 申请架构最大长度，仍返回 128/512 bit，见 vector-length-max.json 和 tools/probe_vector_length.c。没有执行 SME 签名路径。SHAKE 等仍使用共同参考实现。动态调频、温控和系统负载会影响绝对吞吐；表中是本次测试，不是架构理论峰值。更早一轮高频主机驱动数据见 hardware.json，同样在相同核心下比较。

正式数据见 service-crypto.json，含逐轮延迟、PMU、实际频率、验证、摘要和汇总。原生算法来源及公平性说明见 ../../RESEARCH.md。

## 内存带宽

应用内 1 线程、4 种工作集 × 读/写/复制均通过完整数据验证。128 MiB 工作集的结果：

| 操作 | 有效载荷 GB/s | 读写逻辑流量 GB/s |
| --- | ---: | ---: |
| read | 57.11 | 57.11 |
| write | 29.57 | 29.57 |
| copy | 31.73 | 63.47 |

这些是缓存/内存层次上的有效载荷吞吐，不直接等同物理 DRAM 总线带宽。主机补充检查涵盖 1/4 线程、4 种工作集的 24 个组合，全部通过（hardware.json）。应用内服务、历史保存和 JSON/CSV 结构另由 ResearchChecks 验证。

## 频率与恢复

- 实际 ResearchService 完成 5 档、每档 8192 次 NEON 签名，逐个验签，全部恢复上限。见 service-efficiency.json。
- 已观察到运行时系统收紧上限；超出当前范围的档位被跳过，未强行提高系统上限。
- 普通内存取消与频率扫描取消通过；最终扫描取消结果保留 cap_restored=true。见 service-cancel.json、service-cancel_efficiency.json。
- 对原生所有者分别发送 TERM 和 KILL，实测上限恢复。持有扫描时启动第二个同组扫描被文件锁拒绝，见 forced-restoration.json。
- USB 充电中，所有能效排名留空。没有把充电电池净功率解释为 CPU 功耗，未验证放电状态下的数值精度。
- HyperOS 普通应用模式曾改变 CPU 绑定；该样本被拒绝，没有计入有效成绩。Root 模式的正式对照保持 CPU 6 绑定。

## 构建与发布

assembleDebug、assembleDebugAndroidTest、lintDebug 通过；原有监测解析与两次真实采样回归通过（monitor-regression.json）。算法源码与 NIST 向量由 vendor-lock.json 校验；生成对象经反汇编确认向量路径。详见 lab-isa-audit.json、build-manifest.json。

APK SHA-256：`46170f3152770a017d297abbb2afedbacaa5c1e0301a1cb3f6e85d2e1b55631c`。

公开 KAT 私钥只作已知答案和基准输入，不用于真实业务。此次没有刷写分区或修改既有 Pulse 温控配置。
