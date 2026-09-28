// segmenter.rs — 按标点分段算法（精确移植 Kotlin MessageSegmenter，UTF-8 字符级）
//
// 策略：软限制驱动 + 硬限制兜底
// - 软目标：偏好单气泡长度 72、偏好最多气泡数 3；引导「同意图可并、多意图才拆」
// - 硬上限：单气泡 160 字、绝对最多 5 条；仅作兜底
// - 短肯定（嗯/好/行）始终可独立成条
// - 双换行段落视为模型主动给出的语义块边界，但仍受软/硬条数上限约束

/// 分段模式（对齐 Kotlin MessageSegmenter.SplitMode）
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SplitMode {
    /// 语义/行为单元分段 + 软硬条数约束
    Simple,
    /// 按标点逐句 + 长度合并的精细拆分
    Group,
}

const SOFT_TARGET_CHARS: usize = 72;
const SOFT_MAX_SEGMENTS: usize = 3;
const HARD_MAX_CHARS: usize = 160;
const HARD_MAX_SEGMENTS: usize = 5;

/// 句末标点（用于分句）
fn is_terminal_punct(c: char) -> bool {
    matches!(c, '。' | '！' | '？' | '?' | '～' | '…' | '!' | '~' | '⋯')
}

/// 终标正则等价：[。！？!?～…]+$（去尾标点）
fn strip_terminal_punct(text: &str) -> String {
    let mut s = text.trim().to_string();
    while let Some(last) = s.chars().last() {
        if is_terminal_punct(last) {
            s.pop();
        } else {
            break;
        }
    }
    s.trim()
        .trim_matches(|c| matches!(c, '，' | ',' | '；' | ';' | '、' | ' '))
        .to_string()
}

/// 独立短回应（永不并入前后句；软合并时优先跳过）
fn is_standalone_short_reply(core: &str) -> bool {
    if core.is_empty() {
        return true;
    }
    const SHORT_REPLIES: &[&str] = &[
        "嗯", "嗯嗯", "嗯哼", "好", "好的", "好呀", "好啊", "好啦", "行", "行吧", "行啊", "哦",
        "噢", "喔", "啊", "呀", "哈", "哈哈", "哈哈哈", "呵呵", "嘿", "嗨", "是", "是的", "对",
        "对的", "对呀", "可以", "可以啊", "没事", "没事的", "知道了", "收到", "了解", "明白",
        "好吧", "那行", "那好", "得了", "……", "…", "...", "？", "?", "！", "!",
    ];
    if SHORT_REPLIES.contains(&core) {
        return true;
    }
    let interjection_chars = "嗯啊呀哦噢喔哈呵嘿嗨行好对是吧呢嘛啦";
    core.chars().count() <= 2 && core.chars().all(|c| interjection_chars.contains(c))
}

/// 行为/语义切换前缀（新开气泡）
fn looks_like_behavior_shift(sentence: &str) -> bool {
    let s = sentence.trim_start_matches(|c| matches!(c, '，' | ',' | ' ' | '　'));
    const SHIFT_PREFIXES: &[&str] = &[
        "不过", "但是", "可是", "然而", "话说", "对了", "另外", "还有", "所以", "因此", "总之",
        "总而言之", "最后", "好啦", "好了", "行了", "要不", "不如", "建议", "你可以", "你要不要",
        "要不要", "记得", "别忘了", "先去", "先把", "我们先", "那你先", "那你就", "我觉得你可以",
        "我建议", "顺便", "其实", "说真的", "认真的", "换个话题", "不说这个了", "我有点", "我现在",
        "我心里", "我感觉", "我有点想", "我有点累", "抱抱", "来", "走吧", "睡吧", "吃点", "喝点",
    ];
    SHIFT_PREFIXES.iter().any(|p| s.starts_with(p))
}

/// 弱续接前缀（仅当前句语义未完成时才合并）
fn looks_like_weak_continuation(sentence: &str) -> bool {
    let s = sentence.trim_start_matches(|c| matches!(c, '，' | ',' | ' ' | '　'));
    if s.is_empty() || looks_like_behavior_shift(s) {
        return false;
    }
    const WEAK_PREFIXES: &[&str] = &[
        "就", "才", "也", "还", "都", "再", "然后", "接着", "并且", "而且", "吧", "呢", "啊", "呀",
        "啦", "嘛", "哦", "噢", "的", "地", "得", "着", "了", "过",
    ];
    WEAK_PREFIXES.iter().any(|p| s.starts_with(p))
}

/// 按句末标点分句（等价 (?<=[。！？～…!?~])\s*）
fn split_sentences(text: &str) -> Vec<String> {
    let chars: Vec<char> = text.chars().collect();
    let mut sentences = Vec::new();
    let mut current = String::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        current.push(c);
        if is_terminal_punct(c) {
            i += 1;
            while i < chars.len() && chars[i].is_whitespace() {
                i += 1;
            }
            let s = current.trim();
            if !s.is_empty() {
                sentences.push(s.to_string());
            }
            current = String::new();
            continue;
        }
        i += 1;
    }
    let s = current.trim();
    if !s.is_empty() {
        sentences.push(s.to_string());
    }
    sentences
}

/// 是否把 next 续到 current 同一气泡
fn should_continue_same_bubble(current: &str, next: &str) -> bool {
    let current_core = strip_terminal_punct(current);
    let next_core = strip_terminal_punct(next);

    if is_standalone_short_reply(&current_core) || is_standalone_short_reply(&next_core) {
        return false;
    }
    if looks_like_behavior_shift(next) {
        return false;
    }

    let current_complete = current.trim().chars().last().map(is_terminal_punct).unwrap_or(false);
    let weak_continuation = looks_like_weak_continuation(next);

    if !current_complete {
        return true;
    }
    if weak_continuation && next_core.chars().count() <= 6 {
        return true;
    }
    false
}

/// 超长硬切（等价 Kotlin forceSplitLong）
fn force_split_long(text: &str) -> Vec<String> {
    if text.chars().count() <= HARD_MAX_CHARS {
        return vec![text.to_string()];
    }
    let chars: Vec<char> = text.chars().collect();
    let mut result = Vec::new();
    let mut start = 0;
    while chars.len() - start > HARD_MAX_CHARS {
        let end = start + HARD_MAX_CHARS;
        let window = &chars[start..end];
        let mut cut = None;
        for (idx, c) in window.iter().enumerate().rev() {
            if matches!(c, '。' | '！' | '？' | '!' | '?' | '；' | ';' | '，' | ',' | '、' | ' ') {
                cut = Some(idx);
                break;
            }
        }
        let split_at = match cut {
            Some(cut) if cut >= 12 => cut + 1,
            _ => HARD_MAX_CHARS,
        };
        let part: String = chars[start..start + split_at].iter().collect();
        let part = part.trim();
        if !part.is_empty() {
            result.push(part.to_string());
        }
        start += split_at;
        while start < chars.len() && chars[start].is_whitespace() {
            start += 1;
        }
    }
    let rest: String = chars[start..].iter().collect();
    if !rest.trim().is_empty() {
        result.push(rest.trim().to_string());
    }
    if result.is_empty() {
        result.push(text.to_string());
    }
    result
}

/// 语义分块（缓冲 + 续接判断）
fn chunk_by_semantic_units(sentences: Vec<String>) -> Vec<String> {
    if sentences.is_empty() {
        return Vec::new();
    }
    let mut chunks: Vec<String> = Vec::new();
    let mut buffer = String::new();

    for sentence in &sentences {
        let next = sentence.trim().to_string();
        if next.is_empty() {
            continue;
        }
        if buffer.is_empty() {
            if next.chars().count() > HARD_MAX_CHARS {
                chunks.extend(force_split_long(&next));
            } else {
                buffer = next;
            }
            continue;
        }
        let should_continue = should_continue_same_bubble(&buffer, &next);
        let would_exceed_hard = buffer.chars().count() + next.chars().count() > HARD_MAX_CHARS;
        if !should_continue || would_exceed_hard {
            let seg = buffer.trim().to_string();
            if !seg.is_empty() {
                chunks.push(seg);
            }
            buffer = String::new();
            if next.chars().count() > HARD_MAX_CHARS {
                chunks.extend(force_split_long(&next));
            } else {
                buffer = next;
            }
        } else {
            buffer.push_str(&next);
        }
    }
    let seg = buffer.trim().to_string();
    if !seg.is_empty() {
        chunks.push(seg);
    }
    if chunks.is_empty() {
        chunks.push(sentences.join(""));
    }
    chunks
}

/// 软/硬条数上限合并
fn apply_segment_limits(segments: Vec<String>) -> Vec<String> {
    if segments.len() <= 1 {
        return segments;
    }
    let mut result: Vec<String> = segments
        .iter()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    if result.is_empty() {
        return segments;
    }

    while result.len() > SOFT_MAX_SEGMENTS {
        match find_best_merge_index(&result, false) {
            Some(idx) => merge_at(&mut result, idx),
            None => break,
        }
    }
    while result.len() > HARD_MAX_SEGMENTS {
        match find_best_merge_index(&result, true).or_else(|| find_any_merge_index(&result)) {
            Some(idx) => merge_at(&mut result, idx),
            None => break,
        }
    }
    if result.is_empty() {
        segments
    } else {
        result
    }
}

/// 寻找最佳相邻合并点
fn find_best_merge_index(segments: &[String], force: bool) -> Option<usize> {
    let mut best_index: Option<usize> = None;
    let mut best_score = i64::MAX;

    for i in 0..segments.len().saturating_sub(1) {
        let left = &segments[i];
        let right = &segments[i + 1];
        let left_core = strip_terminal_punct(left);
        let right_core = strip_terminal_punct(right);
        let left_standalone = is_standalone_short_reply(&left_core);
        let right_standalone = is_standalone_short_reply(&right_core);

        if !force && (left_standalone || right_standalone) {
            continue;
        }
        if force && left_standalone && right_standalone {
            continue;
        }

        let combined_len = left.chars().count() + right.chars().count();
        if combined_len > HARD_MAX_CHARS {
            continue;
        }

        let mut score = combined_len as i64;
        let distance_to_soft = (combined_len as i64 - SOFT_TARGET_CHARS as i64).abs();
        score += distance_to_soft;
        if looks_like_behavior_shift(right) {
            score += 40;
        }
        if left_standalone || right_standalone {
            score += 30;
        }
        if combined_len <= SOFT_TARGET_CHARS {
            score -= 10;
        }
        if score < best_score {
            best_score = score;
            best_index = Some(i);
        }
    }
    best_index
}

/// 硬兜底：任意可并入硬字数内的相邻对；再不行就并最短对
fn find_any_merge_index(segments: &[String]) -> Option<usize> {
    let mut best_fit: Option<usize> = None;
    let mut best_fit_len = i64::MAX;
    let mut shortest_pair: Option<usize> = None;
    let mut shortest_len = i64::MAX;

    for i in 0..segments.len().saturating_sub(1) {
        let combined_len = (segments[i].chars().count() + segments[i + 1].chars().count()) as i64;
        if combined_len < shortest_len {
            shortest_len = combined_len;
            shortest_pair = Some(i);
        }
        if combined_len <= HARD_MAX_CHARS as i64 && combined_len < best_fit_len {
            best_fit_len = combined_len;
            best_fit = Some(i);
        }
    }
    best_fit.or(shortest_pair)
}

fn merge_at(segments: &mut Vec<String>, index: usize) {
    if index >= segments.len().saturating_sub(1) {
        return;
    }
    let merged = format!("{}{}", segments[index], segments[index + 1]);
    segments[index] = merged;
    segments.remove(index + 1);
}

/// 段落切分（等价 \n{2,}）
fn split_paragraphs(text: &str) -> Vec<String> {
    let mut paras = Vec::new();
    let mut current = String::new();
    let mut newline_run = 0usize;
    for c in text.chars() {
        if c == '\n' {
            newline_run += 1;
            if newline_run >= 2 {
                let p = current.trim();
                if !p.is_empty() {
                    paras.push(p.to_string());
                }
                current = String::new();
            }
            continue;
        }
        newline_run = 0;
        current.push(c);
    }
    let p = current.trim();
    if !p.is_empty() {
        paras.push(p.to_string());
    }
    paras
}

/// SIMPLE 模式分段
fn split_simple(text: &str) -> Vec<String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return vec![trimmed.to_string()];
    }

    let paragraphs: Vec<String> = split_paragraphs(trimmed)
        .into_iter()
        .map(|p| p.trim().to_string())
        .filter(|p| !p.is_blank())
        .collect();

    if paragraphs.len() >= 2 {
        let mut units: Vec<String> = Vec::new();
        for paragraph in &paragraphs {
            if paragraph.chars().count() <= HARD_MAX_CHARS {
                units.push(paragraph.clone());
            } else {
                units.extend(chunk_by_semantic_units(split_sentences(paragraph)));
            }
        }
        let limited = apply_segment_limits(units);
        return if limited.is_empty() { vec![trimmed.to_string()] } else { limited };
    }

    let sentences = split_sentences(trimmed);
    if sentences.is_empty() {
        return vec![trimmed.to_string()];
    }
    if sentences.len() == 1 {
        let only = &sentences[0];
        return if only.chars().count() <= HARD_MAX_CHARS {
            vec![only.clone()]
        } else {
            force_split_long(only)
        };
    }
    apply_segment_limits(chunk_by_semantic_units(sentences))
}

/// GROUP 模式分段（群聊更碎的气泡策略）
fn split_group(text: &str) -> Vec<String> {
    // \n{2,} -> \n
    let mut cleaned = String::new();
    let mut newline_run = 0usize;
    for c in text.chars() {
        if c == '\n' {
            newline_run += 1;
            if newline_run == 1 {
                cleaned.push(c);
            }
            continue;
        }
        newline_run = 0;
        cleaned.push(c);
    }
    let cleaned = cleaned.trim().to_string();
    if cleaned.chars().count() <= 15 {
        return vec![cleaned];
    }

    let raw_segments: Vec<String> = cleaned
        .split('\n')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();

    if raw_segments.len() > 1 {
        let mut result: Vec<String> = Vec::new();
        for segment in &raw_segments {
            if segment.chars().count() <= 20 {
                result.push(segment.clone());
            } else {
                result.extend(split_long_segment(segment));
            }
        }
        return if result.is_empty() { vec![cleaned] } else { result };
    }

    split_long_segment(&cleaned)
}

/// GROUP 长句拆分
fn split_long_segment(text: &str) -> Vec<String> {
    let mut sentences: Vec<String> = Vec::new();
    let mut current = String::new();

    for c in text.chars() {
        current.push(c);
        match c {
            '。' | '！' | '？' => {
                let s = current.trim().to_string();
                if !s.is_empty() {
                    sentences.push(s);
                }
                current = String::new();
            }
            '…' | '～' => {
                if current.chars().count() >= 3 {
                    let s = current.trim().to_string();
                    if !s.is_empty() {
                        sentences.push(s);
                    }
                    current = String::new();
                }
            }
            ',' | '，' => {
                if current.chars().count() >= 10 && current.contains(|c| "！？。".contains(c)) {
                    let s = current.trim().to_string();
                    if !s.is_empty() {
                        sentences.push(s);
                    }
                    current = String::new();
                }
            }
            _ => {}
        }
    }
    let s = current.trim().to_string();
    if !s.is_empty() {
        sentences.push(s);
    }

    if sentences.is_empty() {
        return vec![text.to_string()];
    }

    let mut result: Vec<String> = Vec::new();
    let mut buffer = String::new();

    for sentence in &sentences {
        let clean_sentence: String = sentence
            .trim_start_matches(|c| matches!(c, '，' | ',' | '.' | '。' | ' '))
            .to_string();
        if clean_sentence.is_empty() {
            continue;
        }
        let buffer_len = buffer.chars().count();
        let clean_len = clean_sentence.chars().count();
        if buffer_len + clean_len <= 12 {
            if !buffer.is_empty() {
                buffer.push('，');
            }
            buffer.push_str(&clean_sentence);
        } else {
            if !buffer.is_empty() {
                result.push(buffer.clone());
                buffer = String::new();
            }
            if clean_len <= 14 {
                buffer.push_str(&clean_sentence);
            } else if clean_len <= 25 {
                result.push(clean_sentence);
            } else {
                let chars: Vec<char> = clean_sentence.chars().collect();
                let mid_point = clean_len / 2;
                let end_index = (mid_point + 10).min(clean_len);
                let search_range: Vec<char> = chars[mid_point..end_index].to_vec();
                let split_pos_in_range = search_range
                    .iter()
                    .position(|c| matches!(c, '，' | ',' | '、'));
                if let Some(pos) = split_pos_in_range {
                    let split_pos = mid_point + pos;
                    let first: String = chars[..split_pos + 1].iter().collect();
                    let first = first.trim().to_string();
                    result.push(first);
                    let rest: String = chars[split_pos + 1..].iter().collect();
                    buffer.push_str(rest.trim_start_matches(|c| matches!(c, '，' | ',' | ' ')));
                } else {
                    let first: String = chars[..14].iter().collect();
                    result.push(first.trim_end_matches(|c| matches!(c, '，' | ',')).to_string());
                    let rest: String = chars[14..].iter().collect();
                    buffer.push_str(rest.trim_start_matches(|c| matches!(c, '，' | ',')));
                }
            }
        }
    }
    if !buffer.is_empty() {
        result.push(buffer.clone());
    }
    if result.is_empty() {
        vec![text.to_string()]
    } else {
        result
    }
}

/// 判断文本是否为「纯噪声」
fn is_noise_text(text: &str) -> bool {
    if text.trim().is_empty() {
        return true;
    }
    let noise_chars: Vec<char> = text
        .chars()
        .filter(|c| {
            !matches!(
                c,
                '.' | '…' | '·' | '~' | '～' | ' ' | '　' | '!' | '！' | '?' | '？' | '、' | '，' | ',' | '。'
            ) && !('\u{4E00}'..='\u{9FFF}').contains(c)
        })
        .collect();
    if !noise_chars.is_empty() {
        return false;
    }
    let interjection = [
        "嗯", "嗯嗯", "嗯哼", "唔", "啊", "哦", "噢", "喔", "哈", "哈哈", "呵呵", "嘿", "唉", "呀", "嘛",
        "呢", "吧", "啦", "咯", "呗", "哟", "哇", "诶", "哎", "啧", "嗯呐",
    ];
    let core: String = text
        .chars()
        .filter(|c| c.is_alphanumeric() || ('\u{4E00}'..='\u{9FFF}').contains(c))
        .collect();
    if core.is_empty() {
        return true;
    }
    let core_count = core.chars().count();
    core_count <= 4 && core.chars().all(|c| {
        let word = c.to_string();
        interjection.contains(&word.as_str())
    })
}

trait StrBlank {
    fn is_blank(&self) -> bool;
}
impl StrBlank for str {
    fn is_blank(&self) -> bool {
        self.chars().all(|c| c.is_whitespace())
    }
}

/// 分段入口（UniFFI 导出）
///
/// @param text 原始文本
/// @param mode 拆分模式，默认 Simple
/// @return 分段后的气泡列表（至少 1 项，空输入返回含空串的 1 项）
#[uniffi::export]
pub fn agent_segment(text: String, mode: SplitMode) -> Vec<String> {
    match mode {
        SplitMode::Simple => split_simple(&text),
        SplitMode::Group => split_group(&text),
    }
}

/// 纯噪声判断（UniFFI 导出）
#[uniffi::export]
pub fn agent_is_noise(text: String) -> bool {
    is_noise_text(&text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_input_returns_single_empty() {
        assert_eq!(agent_segment("".into(), SplitMode::Simple), vec![""]);
    }

    #[test]
    fn short_affirmative_stays_single() {
        let r = agent_segment("嗯".into(), SplitMode::Simple);
        assert_eq!(r.len(), 1);
        assert_eq!(r[0], "嗯");
    }

    #[test]
    fn multi_sentence_splits() {
        let text = "今天天气真好呀。我们去散步吧！你觉得呢？";
        let r = agent_segment(text.into(), SplitMode::Simple);
        assert!(r.len() >= 2, "expected >=2 segments, got {r:?}");
    }

    #[test]
    fn hard_max_chars_force_split() {
        let long = "长".repeat(200);
        let r = agent_segment(long.clone(), SplitMode::Simple);
        assert!(r.iter().all(|s| s.chars().count() <= HARD_MAX_CHARS));
        assert!(r.len() >= 2);
    }

    #[test]
    fn group_mode_fine_split() {
        let text = "大家好呀，今天好开心。我们去哪里玩呢？公园怎么样！";
        let r = agent_segment(text.into(), SplitMode::Group);
        assert!(r.len() >= 2, "expected >=2 segments, got {r:?}");
    }

    #[test]
    fn noise_detection() {
        assert!(agent_is_noise("嗯嗯".into()));
        assert!(agent_is_noise("……".into()));
        assert!(!agent_is_noise("今天天气真好".into()));
    }
}
