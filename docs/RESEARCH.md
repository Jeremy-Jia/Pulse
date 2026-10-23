# 向量、内存与频率实验

Pulse 1.9.0 在原来的性能实验室内新增「向量与内存实验」。普通监测、悬浮窗、小部件、传统跑分和 1.8 温控功能保留。

入口：设备概览 → 性能实验室 → 向量与内存实验。

## 内存读写带宽

覆盖顺序读取、顺序写入和复制。工作集为 256 KiB、2 MiB、16 MiB，以及可选的 128/256 MiB。可选 1、4、8 线程；总工作集由线程平均分片，各线程从指定核心开始依次绑核。

- 4096 字节对齐分配，触页、初始化在计时外完成。
- 读取使用显式 NEON 加载和累加，结果与预先计算的校验和比较。
- 写入使用显式 NEON 存储，计时后检查完整目标缓冲区。
- 复制使用系统 libc `memcpy`，计时后进行完整 `memcmp`。
- 每个组合测量 1 秒，重复 1/3/5 轮，汇总多轮 GB/s 中位数。
- GB/s 使用十进制 10^9 字节。复制的有效载荷带宽只计算源字节一次；JSON 另给出两倍的读＋写逻辑流量。缓存写回、写分配和物理 DRAM 总线流量不由该测试直接测量。

小工作集主要反映缓存内访问。大工作集结果也受到系统缓存、内存控制器、频率、线程数和后台任务影响，不能把所有结果统称为 DRAM 峰值带宽。

## ML-DSA-87 完整签名

算法为 FIPS 204 Pure ML-DSA-87，公钥/私钥/签名长度分别为 2592/4896/4627 字节。参考源码固定在 [pq-crystals/dilithium](https://github.com/pq-crystals/dilithium/tree/d35ba3fe5449bee3e6d43e1f296c3ca818bd36be) 的提交 `d35ba3fe5449bee3e6d43e1f296c3ca818bd36be`，原文件和许可证保存在 `app/src/main/cpp/vendor/mldsa-ref`。

四条路径使用同一参考实现及相同编译优化级别 `-O3 -fwrapv -fno-lto`，均关闭编译器自动向量化：

| 路径 | 实际实现 |
| --- | --- |
| 标量 | 参考 C；算法对象使用 `-mgeneral-regs-only`，禁用 NEON 自动生成 |
| NEON | 显式 128 位 SIMD Montgomery 乘法、NTT/逆 NTT 较大层的蝶形和逐系数点乘 |
| SVE | 可伸缩 SVE 显式 SIMD，对应的整数高位乘法、蝶形和点乘 |
| SVE2 | 使用 widening multiply/subtract 和 narrowing shift 指令实现 Montgomery 运算，并用于相同 NTT/点乘路径 |

SHAKE、采样、编码等流程继续使用同一参考实现。本次比较的是上述算术优化对完整签名的贡献，并非各指令集的最佳手写密码库。SME 只检测和展示流式向量长度，本次 ML-DSA 签名不执行 SME 矩阵指令。

每轮固定相同公开测试私钥、1 KiB 消息、空上下文，以及按消息索引派生的固定签名随机输入。不同消息使用不同输入；输入生成在计时外完成。计时包含签名中每次矩阵展开、SHAKE、NTT、拒绝采样和编码，不缓存展开后的密钥/矩阵。该程序只作基准测试，公开测试密钥和可复现随机输入不适用于真实签名业务。

### 正确性与指令校验

- 每条路径先运行 6 组 [NIST ACVP ML-DSA sigGen](https://github.com/usnistgov/ACVP-Server/blob/master/gen-val/json-files/ML-DSA-sigGen-FIPS204/internalProjection.json) 已知答案：tgId 5 的 tcId 61/62/63，以及 tgId 17 的 tcId 241/242/243。包含确定性及带随机输入的签名，按全部签名字节比较，并检查篡改签名会被拒绝。
- 正式计时后的所有签名均逐个验签，验签不计入签名耗时。
- 同轮各路径的整个签名序列必须具有相同 SHAKE256 摘要，否则停止比较。
- 通过 `getauxval`、`PR_SVE_GET_VL`、`PR_SME_GET_VL` 检查运行环境。没有 SVE/SVE2 时在进入相应算法前跳过，派发与 JSON 代码保持基线 ARMv8-A。
- 构建时反汇编 NTT/点乘对象，检查标量对象无向量寄存器使用，NEON/SVE/SVE2 对象确实含目标指令。记录在 `app/build/native/lab-isa-audit.json`，不是只设置编译参数就标为加速。
- 这不表示获得 FIPS 模块认证。

选择 128/512 次签名、1/3/5 轮。每轮轮换路径顺序；速度以多轮吞吐率中位数比较，另给出单次延迟中位数和 P95。指定核心在运行中被系统改变时不生成有效的指定核心成绩。HyperOS 下建议启用 Root 模式；普通应用模式可能被厂商重新设置绑核范围。

## CPU 频率能效扫描

使用所选核心所属 CPUFreq 策略组的普通/Boost 频率表，在当前上限范围内选取约 40/55/70/85/100% 对应的五档上限；不会提高进入每档前的系统上限。每档运行同一轮的 8192 次 ML-DSA-87 NEON 签名。每轮正反交替，保留实际采样频率和可用 PMU。

- 调整 `scaling_max_freq`；没有锁定实际时钟，不修改 governor、最低频率或温控策略。
- 同组核心共享频率策略；实际频率还取决于负载和底层保护。
- 系统在扫描中收紧上限、导致某档不再合法时，跳过该档并记录原因。
- 原生所有者保存原上限，独立子进程监视管道。正常结束、TERM、KILL 都会恢复；共享策略组的文件锁覆盖所有者及恢复守护的生命周期，阻止两个扫描相互覆盖恢复状态。
- 电池达到 48°C 时停止本次实验。现有系统保护继续工作。
- 原始功率为电池电流、电压计算的整机净功率幅值。只有全程放电、采样可读且计时至少 2 秒才生成“签名次数/J”；充电或未知状态不做能效排名。这不是 CPU 单独功耗，也没有减去屏幕和后台系统功耗。

## 报告与复现

后台前台服务提供状态与取消通知。最终记录保存在应用私有目录 `files/research`，支持历史查看、JSON 和 CSV 导出。报告包含逐轮数据、正确性、向量长度、目标与实际频率、PMU 调度比例、取消与恢复情况，缺失计数保留 null。

真机检查入口：

```text
adb shell am instrument -w -r -e research true -e research_scope crypto dev.pulse.monitor.test/dev.pulse.monitor.MonitorInstrumentation
```

`research_scope` 可选 `crypto`、`memory`、`efficiency`、`cancel`、`cancel_efficiency`。这些是主动基准测试：efficiency 会暂时降低频率上限，其他测试会产生真实负载；会保存实验报告。

主机复现工具：`tools/verify_research_device.py`（需要显式 `--sweep` 才扫描频率）、`tools/test_frequency_restore.py`（TERM/KILL 与并发排斥实测）。原始数据和截图见 [1.9 验证记录](verification/research-1.9.0/REPORT.md)。
