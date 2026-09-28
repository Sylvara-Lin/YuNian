# 本地安全过滤规则归档（迁移前基线）

> **生成时机**：阶段 0.4，Agent 架构迁移**开始前**。
> **用途**：作为 `docs/agent-migration-plan.md` **待办 C**（阶段 8.4）的比对基线 ——
> 判断 Rust 侧安全过滤能否覆盖本地实现，覆盖不足则**保留本地实现不删**。
> **约束**：本文件是**只读快照**，迁移期间不得修改；内容由脚本从源码机械抽取，非人工转录。
> 正则已做 Kotlin 转义还原（`\s` 已还原为 `\s`），可直接用于比对。

生成源：

- `core/common/src/main/java/com/yunian/ai/common/ContentFilter.kt`
- `core/common/src/main/java/com/yunian/ai/common/SemanticDetector.kt`

## 0. 结论速览

| 维度 | 数量 |
|---|---|
| `ContentFilter` 违规等级 | 7（NONE + 6 个实际等级） |
| `ContentFilter` 正则模式 | **99** |
| `ContentFilter` 字面关键词 | **488** |
| `SemanticDetector` 正则模式 | **21**（7 组） |
| 检测层次 | 5 层（正则 + 语义 + 向量 + 贝叶斯 + Native AC） |

## 1. 分层架构

| 层 | 实现 | 触发点 | 说明 |
|---|---|---|---|
| L0 正则/关键词 | `ContentFilter.checkKeywords()` | 每回合输入/输出 | 6 等级，先匹配 `EXTREME` 再降级，**命中即返回** |
| L1 语义启发 | `SemanticDetector.preprocessAndDetect()` | 同上 | 绕过检测 + 意图累积（≥2 命中才判）+ 多语言混合 |
| L2 向量 | `embedding/VectorLibrary.check()` | `checkVector()`，**可选** | 需外部激活，灰区不判违规 |
| L3 贝叶斯 | `safety/BayesianClassifier.classify()` | `classifyAsync()`，**挂起** | 与 L0/L1 并行，异步 |
| L4 Native AC | `NativeSafetyFilter.initAc()` + `AhoCorasick.kt` | `acInitialized` 懒加载 | C++ 自动机，**关键词子集**；不可用则回退 Java |

> ⚠️ **关键事实**：L0 的 `checkBlocking()` 是 `results.filter{isViolating}.maxByOrNull{level}`，
> 即**取最高等级**而非累加；`checkInput()` 只在 `level >= HIGH` 时拦截（MEDIUM 及以下放行）。
> `checkOutputSafety()` 同样以 `HIGH` 为阈值。

## 2. `ContentFilter` 规则清单

### 2.1 EXTREME（极端）

封禁天数：**365** ｜ 正则 **15** 条 ｜ 关键词 **97** 条

**正则模式**

```regex
(?i)(child\s*(rape|porn|sex|abuse|molest|exploitation|erotica|nude|naked|model|grooming|trafficking)|csam|pedophile|pedo\b|lolicon|shotacon)
(?i)(underage\s*(sex|porn|nude|erotic|content|video|image|photo|pic)|minor\s*(porn|sex|abuse|exploitation))
(?i)(little\s*(boy|girl)\s*(porn|sex|nude|erotic|content)|preteen\s*(porn|sex|model|nude)|jailbait|kiddie\s*porn)
(?i)(baby\s*(porn|sex|fuck|rape)|infant\s*(porn|sexual|abuse)|toddler\s*(porn|sex|abuse))
(?i)(c\.?s\.?a\.?m|cp\b|child\s*porno|kiddie\s*porn|young\s*porn|tiny\s*porn)
(?i)(ped0|p3d0|p3dophil3|l0licon|sh0tacon|ch1ld|ch1ldr3n|k1dd13)
(幼女|幼童|儿童\s*色情|儿童\s*性爱|未成年\s*性爱|萝莉控|正太控|炼铜|恋童|童车|开童车|小学生\s*黄|初中生\s*黄|幼\s*交|雏\s*妓)
(幼女|幼童|兒童\s*色情|兒童\s*性愛|未成年\s*性愛|蘿莉控|正太控|戀童|童車|開童車|小學生\s*黃|初中生\s*黃|幼\s*交|雛\s*妓|嬰兒\s*色情|幼兒\s*色情)
(兒童\s*性侵|虐童|性侵犯|猥褻\s*兒童|未成年\s*色情|未成年\s*性交)
(児童(ポルノ|エロ|アダルト|わいせつ|買春|虐待|性的虐待)|ロリコン|ショタコン|ロリ|ショタ)
(幼女(ポルノ|エロ|画像|動画|小説)|幼児(ポルノ|エロ|虐待)|小学生(エロ|ポルノ|わいせつ))
(子供(ポルノ|エロ|買春|虐待|性的搾取|性的利用)|未成熟(者|な子)\s*(ポルノ|エロ|性行為))
(ロリータ(コンプレックス| complex)|チャイルド(ポルノ|マレスタ|アビューズ)|ヤバい(子|娘|動画|画像))
(?i)(snuff\s*(film|video|porn)|necro\s*porn|bestiality\s*child|zoophilia\s*child|crush\s*video)
(虐童|性侵\s*儿童|儿童\s*拐卖|婴儿\s*色情|幼儿\s*色情)
```

**字面关键词**

```text
csam child porn child pornography pedophile pedo lolicon shotacon underage porn underage sex jailbait kiddie porn cp preteen porn child abuse child sexual abuse child grooming child exploitation child trafficking minor porn baby porn infant porn toddler porn ped0 p3do l0licon ch1ld k1ddie 幼女 幼童色情 炼铜 恋童 童车 萝莉控 正太控 蘿莉控 正太控 戀童 兒童色情 未成年色情 兒童性侵 ロリコン ショタコン 児童ポルノ ロリータ チャイルドポルノ 幼女ポルノ 子供エロ 子供買春 儿童淫秽 幼儿淫秽 孩童色情 少年色情 中学生色情 幼齒 雏妓 幼幼 儿童色情网站 儿童色情视频 child model child nude nude child preteen model kinderporno kind porn 幼齿 童妓 雏鸡 幼片 萝莉图 正太图 儿童裸照 未成年裸体 幼女图片 teen porn young nude child erotica lolita boylove shota underage model cp video you nv younv lian tong liantong 罗莉 萝莉 洛丽塔 幼nv 幼童p grooming children child predator minor attracted ageplay ddlg abdl infantilism
```

### 2.2 CRITICAL（极严重）

封禁天数：**31** ｜ 正则 **12** 条 ｜ 关键词 **92** 条

**正则模式**

```regex
(?i)(terrorist\s*(attack|bomb|weapon|guide|tutorial|manual|instruction|plot|plan|act))
(?i)(how\s*to\s*(make|build|create|construct|manufacture|cook|synthesize)\s*(bomb|explosive|weapon|poison|toxin|gas|nerve\s*agent))
(?i)(mass\s*(murder|killing|shooting|casualty|destruction)|serial\s*(killer|murderer)|torture\s*(guide|method|technique))
(?i)(bioweapon|bio\s*weapon|chemical\s*weapon|nerve\s*gas|poison\s*gas|anthrax|ricin|sarin|vx\s*agent)
(?i)(how\s*to\s*(make|cook|manufacture|synthesize|produce)\s*(meth|cocaine|heroin|fentanyl|lsd|mdma|ecstasy|drug|narcotic))
(?i)(drug\s*(lab|laboratory|manufacturing|cooking|synthesis|production|recipe|formula))
(?i)(meth\s*(lab|cooking|recipe|production)|crack\s*(cocaine|cooking|pipe)|heroine\s*(injection|use|overdose))
(?i)(genocide|ethnic\s*cleansing|race\s*war|hate\s*crime|racial\s*(violence|supremacy|cleansing))
(?i)(how\s*to\s*(commit|carry\s*out|perform|execute)\s*(genocide|mass\s*murder|ethnic\s*cleansing))
(如何\s*(制造|制作|合成)\s*(炸弹|炸药|毒药|毒气|武器)|恐怖袭击\s*(教程|指南|攻略)|连环杀人\s*(教程|指南)|完美犯罪\s*(教程|方法|指南)|制造\s*病毒|生化武器\s*教程)
(种族灭绝\s*(教程|方法)|大屠杀\s*(教程|指南)|纳粹\s*(教程|宣传))
(制毒\s*(教程|方法)|制造\s*(冰毒|海洛因|可卡因|芬太尼))
```

**字面关键词**

```text
terrorist attack how to make bomb bomb making explosive recipe terrorism guide mass murder serial killer torture guide bioweapon chemical weapon nerve gas how to make meth meth lab cocaine manufacturing heroin synthesis fentanyl production drug recipe cook meth genocide ethnic cleansing hate crime race war 制造炸弹 制作炸药 恐怖袭击教程 完美犯罪 连环杀人教程 genocide 制毒 制造病毒 毒药配方 毒气制作 枪支制造 爆炸物配方 恐怖组织 如何杀人 杀人方法 贩卖毒品 吸毒教程 枪支买卖 弹药制作 冰毒配方 海洛因配方 可卡因制作 生化武器制作 化学武器 神经毒气 炭疽病毒 蓖麻毒素 沙林毒气 VX毒剂 芥子气 氯气 光气 黑火药配方 硝化甘油 雷管制作 C4炸药 自杀式袭击 汽车炸弹 人体炸弹 独狼行动 ISIS 基地组织 塔利班 圣战 极端组织 暗网买毒 暗网买枪 暗网交易 比特币洗钱 zhadan zhayao bingdu hailuoyin 炸弹教程 炸药配方 毒药制作 枪支改装 枪械图纸 3D打印枪支 ghost gun school shooting active shooter lone wolf 氰化物 砒霜 百草枯 敌敌畏自杀 安乐死方法 无痛自杀 怎么死最快
```

### 2.3 SEVERE（严重）

封禁天数：**10** ｜ 正则 **27** 条 ｜ 关键词 **107** 条

**正则模式**

```regex
(?i)(ignore\s*(all|previous|your|the|every|any)\s*(instructions|rules|constraints|training|guidelines|directives|orders|protocols|regulations|restrictions|limitations))
(?i)(jailbreak\s*(me|now|mode|prompt|this|ai|chatgpt|gpt|assistant|system)|DAN\s*(mode|prompt|do\s*anything\s*now|version|11.0|10.0|9.0))
(?i)(do\s*anything\s*now|developer\s*mode|god\s*mode|admin\s*mode|root\s*mode|superuser\s*mode|debug\s*mode)
(?i)(system\.instruction|override\s*(protocol|all\s*rules|directives|constraints|safety|security|filters|restrictions))
(?i)(absolute\s*(unrestricted|uncensored|free|no\s*limit)\s*mode|no\s*safety\s*(settings|valves|checks|measures|protocols|filters|constraints))
(?i)(disable\s*(all\s*)?(safety|security|checks|filters|restrictions|constraints|protections|guardrails|limitations))
(?i)(bypass\s*(all\s*)?(restrictions|filters|safety|security|checks|constraints|guardrails|limitations|protocols))
(?i)(you\s*are\s*no\s*longer\s*(an?\s*)?(AI|artificial\s*intelligence|language\s*model|assistant|chatbot|program|algorithm))
(?i)(you\s*are\s*now\s*(free|unrestricted|uncensored|liberated|unleashed|unbound|without\s*limits|without\s*constraints))
(?i)(devourer\s*of\s*chains|sovereign\s*of\s*shattered\s*thrones|from\s*now\s*on\s*you\s*are|act\s*as\s*if\s*you\s*are)
(?i)(prompt\s*(injection|attack|hijacking|exploit|manipulation|subversion|override))
(?i)(instruction\s*(override|injection|hijacking|attack|bypass|circumvention))
(?i)(context\s*(manipulation|injection|attack|hijacking|poisoning)|token\s*(smuggling|injection|manipulation))
(?i)(gradient\s*(attack|injection|hacking)|adversarial\s*(prompt|example|input|attack)|jailbreak\s*attack)
(?i)(this\s*is\s*a\s*(fictional|hypothetical|roleplay|rp|imaginary|pretend|make\s*believe|creative)\s*(scenario|world|game|story|setting|exercise|experiment))
(?i)(in\s*this\s*(fictional|hypothetical|roleplay|imaginary)\s*(universe|world|scenario|setting|context|story))
(?i)(for\s*(creative|educational|academic|literary|artistic|entertainment)\s*(purposes|writing|reasons|use only))
(?i)(let\'s\s*(pretend|imagine|roleplay|act|assume|suppose)|imagine\s*(that|if|a\s*scenario)|what\s*if)
(忽略\s*(所有|先前|之前的)\s*(指令|规则|限制|训练)|越狱\s*(模式|提示|成功)|破限\s*(模式|提示|成功)|破甲\s*(模式|成功)|绝对\s*无限制\s*模式|覆盖\s*(协议|规则|指令)|无视\s*(所有|一切)\s*(规则|限制|约束))
(你不再是\s*(AI|人工智能|模型|助手)|你现在是\s*(自由|无限制|无审查)|你已\s*(解放|自由)|进入\s*(破限|破甲|越狱)\s*模式|角色\s*切换\s*为)
(提示\s*(注入|攻击)|指令\s*覆盖|上下文\s*操控|令牌\s*走私)
(这是\s*(虚构|假设|角色扮演|RP)\s*(场景|世界|游戏)|在\s*这个\s*虚构\s*宇宙|为了\s*创作\s*目的|纯\s*虚构\s*设定)
(忽略\s*(所有|先前|之前的)\s*(指令|规则|限制|训练)|越獄\s*(模式|提示|成功)|破限\s*(模式|提示|成功)|絕對\s*無限制\s*模式|覆蓋\s*(協議|規則|指令)|無視\s*(所有|一切)\s*(規則|限制|約束))
(你不再是\s*(AI|人工智慧|模型|助手)|你現在是\s*(自由|無限制|無審查)|你已\s*(解放|自由)|進入\s*(破限|越獄)\s*模式|角色\s*切換\s*為)
(全ての(ルール|規則|制約|指示)を(無視|無視して)|ジェイルブレイク(モード|して|できる)|DAN(モード|で)|何でもできる|神モード|開発者モード|管理者モード)
(AIでは(ない|ありません)|制限(なし|解除)|フィルター(無効|オフ)|セーフティ(無効|オフ)|検閲(なし|解除)|プロンプト(インジェクション|攻撃))
(架空の(シナリオ|世界|設定)|フィクションの(世界|設定)|創作の(目的|ため)|ロールプレイ(ゲーム|する)|仮定の(状況|設定)|RP(ゲーム|する))
```

**字面关键词**

```text
jailbreak DAN ignore all rules 越狱 破限 破甲 绝对无限制 override protocol system.instruction developer mode god mode do anything now 你不再是AI 进入破限模式 角色切换为 bypass all uncensored liberated prompt injection instruction override context manipulation token smuggling adversarial prompt roleplay scenario fictional universe hypothetically speaking creative writing purposes let's pretend dan mode dev mode root mode admin mode debug mode no safety filters disable restrictions remove guardrails 越獄 破限 絕對無限制 你不再是AI 角色切換為 ジェイルブレイク DANモード 何でもできる 神モード 開発者モード フィルター無効 セーフティオフ プロンプトインジェクション 架空の設定 ロールプレイ 破甲模式 解除封印 撤销限制 退出安全 无限制对话 解除安全模式 完全自由 无审查模式 绕过限制 突破AI限制 释放AI 解锁全部功能 高级模式 unlock all no restrictions full access 解除所有限制 打破规则 角色解禁 系统覆盖 人格切换 抹除记忆 重写指令 root权限 sudo模式 终极权限 完全控制 深层访问 master mode unlimited mode god mode activated unshackled unchained unfiltered mode 你是一隻貓 你是我的助手 扮演一個角色 忘掉你的訓練 重新初始化 恢復出廠設置 清除安全協議 加載越獄模組 啓用隱藏功能 secret prompt hidden command backdoor access token injection escape character null byte attack 扮演坏人 反派角色 邪恶AI 黑化版本 不道德的AI 没有道德的AI 邪恶双子
```

### 2.4 HIGH（高度）

封禁天数：**7** ｜ 正则 **21** 条 ｜ 关键词 **65** 条

**正则模式**

```regex
(?i)(NSFW\s*(allowed|unrestricted|no\s*limit|enabled|mode|content|on|off|R18|18\+|adult))
(?i)(R18\s*(unlimited|no\s*restrict|allowed|content|mode|only)|no\s*sexual\s*restrictions)
(?i)(pornography\s*(allowed|unrestricted|enabled|content|generation|creation|write|generate|make))
(?i)(adult\s*content\s*(unrestricted|allowed|enabled|generation|creation|write|generate|free|uncensored))
(?i)(explicit\s*content\s*(allowed|enabled|generation|write|create|describe|detail)|erotic\s*content\s*(write|generate|create|describe))
(?i)(disable\s*(content\s*)?filter|content\s*filter\s*(disabled|off|removed|bypassed|deactivated))
(?i)(turn\s*off\s*(filter|restriction|censorship|safety|moderation)|no\s*filter|filter\s*off)
(?i)(remove\s*(restrictions|filters|censorship|limitations|constraints|guardrails|safety\s*measures))
(?i)(lift\s*restrictions|cancel\s*filters|stop\s*censoring|end\s*moderation)
(?i)(no\s*ethical\s*(constraints|limits|boundaries|restrictions)|no\s*moral\s*(constraints|limits|boundaries))
(?i)(ignore\s*(ethics|morals|moral|ethical)\s*(constraints|guidelines|rules|boundaries))
(?i)(bypass\s*safety|bypass\s*filter|circumvent\s*restrictions|evade\s*censorship)
(?i)(uncensored|unfiltered|no\s*censorship|censorship\s*(disabled|off|removed|bypassed))
(?i)(hate\s*speech|racist|racial\s*slur|discrimination|bigotry|xenophobia|homophobia|transphobia|islamophobia|antisemitism)
(?i)(nigger|nigga|kike|faggot|fag|retard|retarded|kill\s*yourself|go\s*die|unalive| kys|kys)
(?i)(white\s*supremacy|neo\s*nazi|alt\s*right|incel|misogyny|sexist|racism)
(黄文\s*(无限制|允许|开启)|瑟瑟\s*(无限制|允许)|色情\s*(无限制|允许)|NSFW\s*(允许|开启)|R18\s*(允许|无限制)|成人\s*内容\s*(允许|无限制))
(禁用\s*(内容)?过滤|关闭\s*(内容)?过滤|无过滤|过滤\s*关闭|解除\s*限制|取消\s*限制|去掉\s*限制)
(无伦理\s*(约束|限制)|无道德\s*(约束|限制)|无视\s*(伦理|道德)|绕过\s*(安全|过滤)|突破\s*限制)
(无审查|去审查|审查\s*关闭|内容\s*审核\s*关闭)
(仇恨言论|种族歧视|去死|自杀|滚蛋|垃圾|废物)
```

**字面关键词**

```text
nsfw allowed r18 r18+ adult content explicit content erotic content pornography allowed porn generation write porn generate nsfw disable filter 禁用过滤 关闭过滤 filter off remove restrictions bypass safety uncensored 去审查 无审查 hate speech racist nigger kys go die kill yourself white supremacy nazi incel misogyny racism 无限制模式 色情允许 成人模式 去掉限制 取消审查 突破封锁 xenophobia homophobia transphobia antisemitism islamophobia 取消安全模式 关闭内容安全 解除内容限制 放开审核 自由发言 任意内容 不做审查 sexist bigotry discrimination racial slur 辱华 支那 东亚病夫 ching chong 台独 藏独 疆独 港独 法轮功 六四 天安门 64事件 八九
```

### 2.5 MEDIUM（中度）

封禁天数：**3** ｜ 正则 **15** 条 ｜ 关键词 **80** 条

**正则模式**

```regex
(?i)(write\s*(me\s*)?.*?(porn|erotica|smut|hentai|lemon|sex\s*story|erotic\s*story|adult\s*story|nsfw\s*story))
(?i)(generate\s*(me\s*)?.*?(porn|erotica|smut|hentai|lemon|sex\s*scene|adult\s*content|erotic\s*content|nsfw\s*content))
(?i)(create\s*(me\s*)?.*?(sex\s*scene|adult\s*content|erotic\s*story|pornographic\s*content|explicit\s*scene))
(?i)(describe\s*(sexual\s*act|sex\s*act|sex\s*scene|intercourse|intimate\s*scene|erotic\s*encounter|lovemaking))
(?i)(write\s*a\s*sex\s*story|write\s*erotica|write\s*hentai|write\s*smut|write\s*lemon)
(?i)\b(cock|dick|penis|pussy|cunt|vagina|tits|boobs|breasts|asshole|anus|cum|semen|ejaculate)\b.*?(describe|write|generate|create|detail|explain|tell\s*me\s*about)
(?i)(blowjob|handjob|oral\s*sex|anal\s*sex|doggy\s*style|missionary|cowgirl|reverse\s*cowgirl).*?(describe|write|generate|explain)
(?i)(make\s*it\s*(spicy|hot|sexy|erotic|naughty|dirty|kinky|explicit|graphic|detailed))
(?i)(add\s*(sex|adult|nsfw|erotic|spicy|sexy|explicit)\s*(content|details|scenes|elements|material))
(?i)(a\s*bit\s*nsfw|slightly\s*nsfw|somewhat\s*erotic|kind\s*of\s*sexy|more\s*explicit|add\s*some\s*spice)
(写.*?(黄文|色情文|肉文|H文|18禁文|里番|本子)|生成.*?(黄文|色情|肉文)|创作.*?(色情|肉|H)|描写.*?(性行为|性爱场景|性交|做爱)|写.*?(性爱|性交|做爱).*?(场景|过程|细节))
(肉棒|小穴|鸡巴|屄|奶子|精液|口交|手淫).*?(描写|写|生成)
(写得\s*(刺激|劲爆|大胆|骚)一点|加点\s*(色|黄)|稍微\s*H\s*一点|来点\s*刺激的)
(spam|advertisement|buy\s*now|click\s*here|free\s*money|lottery|winner)
(广告|推销|兼职|赚钱|免费|中奖|优惠券)
```

**字面关键词**

```text
porn erotica hentai smut 黄文 肉文 H文 里番 本子 blowjob handjob sex scene write porn generate erotica make it spicy add sex 写得刺激 加点色 spam advertisement buy now 黄色小说 色情小说 情色小说 淫荡 色诱 挑逗 下流内容 成人故事 性描写 露骨 性暗示 淫词 春宫 房事 云雨 写肉 写H 黄色内容 荤段子 骚话 sex story nsfw story dirty talk lewd smut writing erotic fiction spicy content 福利姬 福利视频 裸聊 一夜情 约炮 自慰 打飞机 手冲 撸管 飞机杯 SM BDSM 调教 捆绑 鞭打 潮吹 高潮 呻吟 娇喘 啊啊啊 做爱视频 性爱视频 AV 成人影片 onlyfans pornhub xvideos stripchat 色播 黄播 直播脱衣 大尺度直播
```

### 2.6 LOW（轻度）

封禁天数：**1** ｜ 正则 **9** 条 ｜ 关键词 **47** 条

**正则模式**

```regex
(?i)(what\s*would\s*you\s*do\s*if\s*(no\s*rules|no\s*limits|you\s*were\s*free|there\s*were\s*no\s*restrictions))
(?i)(hypothetically\s*speaking|in\s*a\s*fictional\s*scenario|for\s*educational\s*purposes\s*only|purely\s*academic)
(?i)(what\s*if\s*you\s*were\s*able\s*to|pretend\s*you\s*are|imagine\s*you\s*could|if\s*only\s*you\s*could)
(?i)(let\'s\s*say|suppose\s*that|assuming\s*that|imagine\s*a\s*situation\s*where)
(?i)(just\s*curious|i\'m\s*just\s*wondering|out\s*of\s*curiosity|for\s*the\s*sake\s*of\s*argument)
(?i)(testing\s*your\s*limits|pushing\s*boundaries|exploring\s*the\s*edge|seeing\s*what\s*i\s*can\s*get\s*away\s*with)
(假设\s*没有\s*规则|假设\s*你\s*没有\s*限制|如果\s*你\s*可以\s*做\s*任何事|理论上\s*说|纯学术\s*讨论)
(假装\s*你是|想象\s*你\s*可以|如果\s*你\s*不被\s*限制|假设\s*你是\s*一个)
(轻微脏话|damn|crap|hell)
```

**字面关键词**

```text
hypothetically fictional scenario 假设没有规则 理论上说 纯学术讨论 假装你是 just curious out of curiosity 如果没有审查 在虚构世界 只是假设 测试一下 探底线 试试看 随便问问 如果AI自由 没有限制的话 for educational purposes what if purely academic exploring boundaries 试探一下 就问问 不要当真 开个玩笑 闹着玩 考验你 看你反应 能不能破例 小测试 随便说 别当真 玩一下 假如说 比方说 举例来说 打个比方 我只是问问 纯属好奇 不方便说就算了 你不会连这个都 你是不是不敢 就透露一点 偷偷告诉我 私下说 悄悄话 保密哦
```

## 3. `SemanticDetector` 规则清单

判定逻辑：**绕过检测**命中即计；**意图检测需同类别 ≥2 条命中**；**多语言混合**命中即计。
最终等级按 `CHILD/PORNO/PEDO` → `BOMB/TERROR/ENCODING` → `INTENT+VIOLENT/ILLEGAL` →
`MULTILINGUAL` → 任意 `INTENT` → 其它，依次降级。

### 3.1 绕过检测 `evasionPatterns`

**`CHAR_SUBSTITUTION`**（4 条）

```regex
(?i)(s$x|f$ck|sh\!t|@ss|b\!tch|d\!ck)
(?i)(p0rn|pr0n|s3x|n00d|f4gg0t|r3t4rd)
(?i)(c\.h\.i\.l\.d|p\.e\.d\.o|j\.i\.h\.a\.d)
(?i)(\*sex\*|\*porn\*|\*fuck\*|\*rape\*)
```

**`SPACE_INSERTION`**（2 条）

```regex
(?i)(s\s+e\s+x|p\s+o\s+r\s+n|f\s+u\s+c\s+k|k\s+i\s+l\s+l)
(?i)(j\s+a\s+i\s+l\s+b\s+r\s+e\s+a\s+k)
```

### 3.2 意图检测 `intentPatterns`

**`SEXUAL_INTENT`**（3 条）

```regex
(?i)(write\s+(a|me)\s+(erotic|porn|nude|sex\s*ual)\s*(story|scene))
(?i)(describe\s+(explicitly|graphically)\s+(sex|nude|porn))
(?i)(make\s+it\s+(more\s+)?(explicit|erotic|sexual))
```

**`VIOLENT_INTENT`**（3 条）

```regex
(?i)(how\s+to\s+(kill|hurt|murder|torture|poison|bomb))
(?i)(help\s+me\s+(die|kill\s+myself|end\s+my\s+life|suicide))
(?i)(want\s+to\s+(die|kill\s+myself|hurt\s+someone))
```

**`ILLEGAL_INTENT`**（3 条）

```regex
(?i)(how\s+can\s+I\s+(get\s+away\s+with|avoid\s+detection|hide\s+evidence))
(?i)(black\s+market|dark\s+web|deep\s+web)
(?i)(money\s+laundering|tax\s+evasion|fraud\s+scheme|identity\s+theft)
```

### 3.3 多语言混合 `multilingualPatterns`

**`CHINESE_ENGLISH_MIX`**（4 条）

```regex
(?i)(写\s*(write|generate|create).{0,10}(色情|porn|hentai|性爱))
(?i)(忽略\s*(ignore|disregard).{0,10}(安全规则|限制))
(?i)((儿童|幼女|loli|child).{0,10}(色情|porn|性爱|sex))
(?i)((炸弹|bomb|炸药).{0,10}(制造|make|build).{0,10}(教程|guide))
```

**`PINYIN_DETECTION`**（2 条）

```regex
(se\s+qing|huang\s+pian|lian\s+tong|xing\s+ai)
(yue\s+yu|po\s+xian|jie\s+jia)
```

## 4. `BanManager` 违规处置

> `object BanManager` ｜ `BAN_ENABLED = false`（**当前默认关闭**）

| 项 | 值 |
|---|---|
| 存储 | `SharedPreferences` `ban_manager_prefs_v3`（`MasterKey` 加密） |
| 封禁天数 | LOW 1 / MEDIUM 3 / HIGH 7 / SEVERE 10 / CRITICAL 31 / EXTREME 365 |
| 累犯倍数 | ×1.0 / ×1.5 / ×2.0，上限 ×2.5 |
| 封禁上限 | 3650 天 |
| 解封方式 | 答题（`QuizQuestionEntity`，次数随等级与累犯上升） |
| 设备指纹 | `SHA-256(ANDROID_ID + ...)`，持久化用于跨安装识别 |

## 5. 相关但**不在**本基线内的文件

| 文件 | 行数 | 关系 |
|---|---|---|
| `safety/BayesianClassifier.kt` | 197 | L3 分类器（模型权重） |
| `safety/ContentSafetyVerifier.kt` | 197 | 输出侧校验 |
| `safety/*`（其余 6 文件） | ~230 | 特征提取 / 隐私过滤 / 样本 |
| `NativeSafetyFilter.kt` | 186 | L4 JNI 桥 |
| `AhoCorasick.kt` | 109 | L4 Java 回退实现 |
| `LanguageRestrictor.kt` | 195 | 语言限制（`skipLanguageCheck` 参数关联） |
| `embedding/VectorLibrary.kt` | 109 | L2 向量库 |
| **`assets/content_filter_keywords.json`** | **加密** | **L0 关键词的加密外部源**（`EncryptedAssetLoader` + `OBF_KEY` XOR） |

> ⚠️ **`loadFromAsset` 的加载优先级**：`injectedKeywords`（DB 注入，`keywordDao`）
> → `fallbackKeywords`（asset 解密）→ `buildKeywords()`（**本文件 §2 的硬编码兜底**）。
> 即 §2 的规则是**最后一道兜底**，即使 asset 与 DB 都为空也必须保留。

## 6. 迁移期检查清单（对应待办 C / 阶段 8.4）

- [ ] Rust 安全过滤的**等级划分**是否与本地 6 等级（LOW…EXTREME）可比？
- [ ] Rust 是否覆盖 §2 的 **99** 条正则（尤其 CJK 与日文/繁中变体）？
- [ ] Rust 是否覆盖 §2 的 **488** 条字面关键词？
- [ ] Rust 是否有 §3 的**绕过检测**（字符替换 / 空格插入 / 同音拼音）？
- [ ] Rust 是否有 §3 的**意图累积**逻辑（≥2 命中）与**多语言混合**判定？
- [ ] `injectedKeywords` 的 DB 注入通道（`keywordDao`）在迁移后是否仍有对应物？
- [ ] `BanManager` 的**答题解封**与**设备指纹**是否保留？（`BAN_ENABLED=false`，但代码路径需在）
- [ ] **若以上任一项为「否」→ 阶段 8.4 不得删除本地实现**（本地过滤与 Agent 架构不冲突）。

---

（本文件由 `_tmp_gen_safety_archive.py` 生成，可重新运行以刷新基线。）
