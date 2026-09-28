// card_import.rs — 角色卡导入（聊天陪伴 · 复用开源规范）。
//
// 复用 chara_card（Apache-2.0）：V1/V2/V3 角色卡 JSON 解析（shm 统一模型）。
// PNG 提取（Tavern V2 事实标准：tEXt chunk "chara" 内嵌 base64 JSON）为本库未覆盖的
// 格式边界，此处做最小 chunk 遍历（无自研格式）。

use chara_card::raw::CharacterCard;

/// 角色卡信息（导入预览 + 世界书提取）。
#[derive(Clone, Debug, Default, uniffi::Record)]
pub struct CharacterCardInfo {
    pub name: String,
    pub description: String,
    pub personality: String,
    pub first_mes: String,
    pub system_prompt: String,
    /// 内嵌世界书 JSON（角色卡 character_book；无则空串，社区格式可再入 Lorebook::parse）
    pub world_json: String,
}

/// 从 JSON 字符串解析角色卡（V1/V2/V3 自动）。
pub fn parse_character_card_json(json: &str) -> Result<CharacterCardInfo, String> {
    // 双路径：先经 chara_card（spec 合规）；失败回退宽松提取（Tavern 事实格式：
    // character_book.entries 为 map，chara_card 仅认数组）
    if let Ok(card) = serde_json::from_str::<CharacterCard>(json) {
        return card_to_info(card);
    }
    fallback_extract(json)
}

/// 宽松回退：不依赖 chara_card，按社区事实字段提取（含 character_book 原始 JSON）。
fn fallback_extract(json: &str) -> Result<CharacterCardInfo, String> {
    let value: serde_json::Value = serde_json::from_str(json)
        .map_err(|e| format!("角色卡 JSON 解析失败: {e}"))?;
    let data = value.get("data").unwrap_or(&value);
    let get = |k: &str| data.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
    let world_json = data
        .get("character_book")
        .map(|w| w.to_string())
        .unwrap_or_default();
    Ok(CharacterCardInfo {
        name: get("name"),
        description: get("description"),
        personality: get("personality"),
        first_mes: get("first_mes"),
        system_prompt: get("system_prompt"),
        world_json,
    })
}

/// 从 PNG 字节解析角色卡（Tavern V2：tEXt chunk "chara" 内嵌 base64 JSON）。
pub fn parse_character_card_png(bytes: &[u8]) -> Result<CharacterCardInfo, String> {
    let json = extract_tavern_png_json(bytes)?;
    parse_character_card_json(&json)
}

/// PNG tEXt 遍历：找 keyword == "chara" 的文本块（Tavern 角色卡 V2 事实标准）。
/// PNG 结构：8 字节签名 + (len u32 BE, type 4B, data, crc u32) 分块。
fn extract_tavern_png_json(bytes: &[u8]) -> Result<String, String> {
    if bytes.len() < 8 || &bytes[..8] != b"\x89PNG\r\n\x1a\n" {
        return Err("不是有效的 PNG 文件".to_string());
    }
    let mut offset = 8usize;
    while offset + 8 <= bytes.len() {
        let len = u32::from_be_bytes(bytes[offset..offset + 4].try_into().unwrap()) as usize;
        let chunk_type = &bytes[offset + 4..offset + 8];
        let data_start = offset + 8;
        let data_end = data_start + len;
        if data_end + 4 > bytes.len() {
            break;
        }
        if chunk_type == b"tEXt" {
            let data = &bytes[data_start..data_end];
            if let Some(nul) = data.iter().position(|&b| b == 0) {
                let keyword = &data[..nul];
                if keyword == b"chara" {
                    let b64 = &data[nul + 1..];
                    let json_bytes = base64_decode(b64)?;
                    return String::from_utf8(json_bytes)
                        .map_err(|e| format!("角色卡 JSON 编码非法: {e}"));
                }
            }
        }
        offset = data_end + 4; // 跳过 CRC
    }
    Err("PNG 中未找到角色卡数据（tEXt/chara）".to_string())
}

/// 标准 base64 解码（PNG tEXt 用标准字母表；容忍 padding 缺失）。
fn base64_decode(input: &[u8]) -> Result<Vec<u8>, String> {
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = Vec::with_capacity(input.len() / 4 * 3);
    let mut buf: u32 = 0;
    let mut bits = 0u32;
    for &b in input {
        if b == b'=' {
            break;
        }
        let val = match TABLE.iter().position(|&t| t == b) {
            Some(v) => v as u32,
            None => return Err("角色卡 base64 含非法字符".to_string()),
        };
        buf = (buf << 6) | val;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
        }
    }
    Ok(out)
}

fn card_to_info(card: CharacterCard) -> Result<CharacterCardInfo, String> {
    match card {
        CharacterCard::Flat(v1) => Ok(CharacterCardInfo {
            name: v1.name,
            description: v1.description,
            personality: v1.personality,
            first_mes: v1.first_mes,
            // V1 无 system_prompt 字段（V2 扩展）
            system_prompt: String::new(),
            world_json: String::new(),
        }),
        CharacterCard::Nested(nested) => {
            let v1 = &nested.data.v1;
            let world_json = match &nested.data.v2 {
                Some(v2) => match &v2.character_book {
                    Some(book) => serde_json::to_string(book)
                        .map_err(|e| format!("世界书序列化失败: {e}"))?,
                    None => String::new(),
                },
                None => String::new(),
            };
            Ok(CharacterCardInfo {
                name: v1.name.clone(),
                description: v1.description.clone(),
                personality: v1.personality.clone(),
                first_mes: v1.first_mes.clone(),
                system_prompt: nested.data.v2.as_ref().map(|v2| v2.system_prompt.clone()).unwrap_or_default(),
                world_json,
            })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_flat_v1_card_json() {
        // Flat 变体 = 纯 v1 扁平结构（无 spec 字段）
        let json = r#"{"name":"小恋","description":"咖啡馆主理人","personality":"温柔","scenario":"","first_mes":"你好呀","mes_example":""}"#;
        let info = parse_character_card_json(json).unwrap();
        assert_eq!(info.name, "小恋");
        assert_eq!(info.description, "咖啡馆主理人");
        assert!(info.world_json.is_empty());
    }

    #[test]
    fn parses_nested_v2_card_with_world() {
        // Tavern 事实格式：entries 为 map → 走宽松回退
        let json = r#"{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"测试卡","description":"desc","personality":"p","scenario":"","first_mes":"hi","mes_example":"","system_prompt":"sp","character_book":{"name":"内嵌世界","entries":{"1":{"keys":["关键词"],"content":"内容","insertion_order":1,"enabled":true}}}}}"#;
        let info = parse_character_card_json(json).unwrap();
        assert_eq!(info.name, "测试卡");
        assert!(!info.world_json.is_empty(), "应提取内嵌世界书");
        // 提取的世界书可被我们的引擎解析
        let wb = crate::lorebook::Lorebook::parse(&info.world_json).expect("world json 可解析");
        assert_eq!(wb.name.as_deref(), Some("内嵌世界"));
    }

    #[test]
    fn png_without_chara_chunk_errors() {
        let png = [0x89u8, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
        assert!(parse_character_card_png(&png).is_err());
    }

    #[test]
    fn png_with_chara_chunk_extracts_json() {
        let json = r#"{"name":"PNG卡","description":"","personality":"","scenario":"","first_mes":"","mes_example":""}"#;
        let b64 = "eyJuYW1lIjoiUE5H5Y2hIiwiZGVzY3JpcHRpb24iOiIiLCJwZXJzb25hbGl0eSI6IiIsInNjZW5hcmlvIjoiIiwiZmlyc3RfbWVzIjoiIiwibWVzX2V4YW1wbGUiOiIifQ==";
        // 构造最小 PNG：签名 + tEXt chunk(keyword=chara, text=base64) + IEND
        let mut png: Vec<u8> = vec![0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
        let text = format!("chara\0{b64}");
        let mut chunk = Vec::new();
        chunk.extend_from_slice(&(text.len() as u32).to_be_bytes());
        chunk.extend_from_slice(b"tEXt");
        chunk.extend_from_slice(text.as_bytes());
        chunk.extend_from_slice(&[0u8; 4]); // CRC 占位（解析不校验）
        png.extend_from_slice(&chunk);
        png.extend_from_slice(&0u32.to_be_bytes());
        png.extend_from_slice(b"IEND");
        png.extend_from_slice(&[0u8; 4]);
        let info = parse_character_card_png(&png).unwrap();
        assert_eq!(info.name, "PNG卡");
    }
}