use crc::{Algorithm, Crc, CRC_32_ISCSI, CRC_32_ISO_HDLC};
use serde::{Serialize, Serializer};
use std::collections::BTreeMap;

const POSTCARD_VERSION: &str = "1.1.3";
const UPSTREAM_COMMIT: &str = "718aa6a6850456017c19eeff67303c633f875736";
const POSTINO_SOURCE: &str = "interop/rust-fixtures/src/main.rs";
const UPSTREAM_LOOPBACK: &str = "https://github.com/jamesmunns/postcard/blob/718aa6a6850456017c19eeff67303c633f875736/source/postcard/tests/loopback.rs#L62-L156";
const UPSTREAM_COBS: &str = "https://github.com/jamesmunns/postcard/blob/718aa6a6850456017c19eeff67303c633f875736/source/postcard/src/ser/mod.rs#L22-L48";
const UPSTREAM_CRC: &str = "https://github.com/jamesmunns/postcard/blob/718aa6a6850456017c19eeff67303c633f875736/source/postcard/tests/crc.rs#L1-L19";

#[derive(Serialize)]
struct Sensor<'a> {
    id: u16,
    temp: i32,
    label: &'a str,
}

#[derive(Serialize)]
struct Envelope<'a> {
    sensor: Sensor<'a>,
    readings: Vec<i16>,
    note: Option<&'a str>,
    bytes: &'a [u8],
}

#[derive(Serialize)]
struct UpstreamBasic {
    st: u16,
    ei: u8,
    sf: u64,
    tt: u32,
}

#[derive(Serialize)]
enum UpstreamBasicEnum {
    #[allow(dead_code)]
    Bib,
    Bim,
    #[allow(dead_code)]
    Bap,
}

#[derive(Serialize)]
struct UpstreamEnumStruct {
    eight: u8,
    sixt: u16,
}

#[derive(Serialize)]
enum UpstreamDataEnum {
    Bib(u16),
    Bim(u64),
    Bap(u8),
    Kim(UpstreamEnumStruct),
    Chi { a: u8, b: u32 },
    Sho(u16, u8),
}

#[derive(Serialize)]
struct UpstreamNewtype(u32);

#[derive(Serialize)]
struct UpstreamTupleStruct((u8, u16));

#[derive(Serialize)]
enum Message<'a> {
    Ping,
    Pong { id: u16 },
    Data(&'a [u8]),
}

#[derive(Serialize)]
enum DerivedMessage<'a> {
    Ping,
    Pong { id: u16 },
    Data(&'a [u8]),
}

enum WideMessage {
    High,
}

impl Serialize for WideMessage {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        match self {
            WideMessage::High => serializer.serialize_unit_variant("WideMessage", 128, "High"),
        }
    }
}

fn print_fixture<T: Serialize + ?Sized>(name: &str, schema: &str, value: &str, input: &T) {
    let bytes = postcard::to_stdvec(input).unwrap();
    print_vector(name, "raw", schema, value, &bytes, POSTINO_SOURCE);
}

fn print_cobs_fixture<T: Serialize + ?Sized>(name: &str, schema: &str, value: &str, input: &T) {
    let bytes = postcard::to_stdvec_cobs(input).unwrap();
    print_vector(name, "cobs", schema, value, &bytes, POSTINO_SOURCE);
}

fn print_crc_fixture<T: Serialize + ?Sized>(
    name: &str,
    schema: &str,
    value: &str,
    algorithm: &'static Algorithm<u32>,
    input: &T,
) {
    let crc = Crc::<u32>::new(algorithm);
    let bytes = postcard::to_stdvec_crc32(input, crc.digest()).unwrap();
    print_vector(
        name,
        "crc32-iso-hdlc",
        schema,
        value,
        &bytes,
        POSTINO_SOURCE,
    );
}

fn print_upstream_fixture<T: Serialize + ?Sized>(
    name: &str,
    schema: &str,
    value: &str,
    expected: &[u8],
    input: &T,
) {
    let bytes = postcard::to_stdvec(input).unwrap();
    assert_eq!(
        bytes, expected,
        "upstream postcard fixture '{name}' changed"
    );
    print_vector(name, "raw", schema, value, &bytes, UPSTREAM_LOOPBACK);
}

fn print_upstream_cobs_fixture<T: Serialize + ?Sized>(
    name: &str,
    schema: &str,
    value: &str,
    expected: &[u8],
    input: &T,
) {
    let bytes = postcard::to_stdvec_cobs(input).unwrap();
    assert_eq!(
        bytes, expected,
        "upstream postcard fixture '{name}' changed"
    );
    print_vector(name, "cobs", schema, value, &bytes, UPSTREAM_COBS);
}

fn print_upstream_crc_fixture<T: Serialize + ?Sized>(
    name: &str,
    schema: &str,
    value: &str,
    expected: &[u8],
    input: &T,
) {
    let crc = Crc::<u32>::new(&CRC_32_ISCSI);
    let bytes = postcard::to_stdvec_crc32(input, crc.digest()).unwrap();
    assert_eq!(
        bytes, expected,
        "upstream postcard fixture '{name}' changed"
    );
    print_vector(name, "crc32-iscsi", schema, value, &bytes, UPSTREAM_CRC);
}

fn print_vector(name: &str, flavor: &str, schema: &str, value: &str, bytes: &[u8], source: &str) {
    for (field_name, field) in [
        ("name", name),
        ("flavor", flavor),
        ("schema", schema),
        ("value", value),
        ("source", source),
    ] {
        assert!(
            !field.contains(['\t', '\n', '\r']),
            "fixture {field_name} must fit in one TSV field"
        );
    }
    assert!(
        matches!(flavor, "raw" | "cobs" | "crc32-iso-hdlc" | "crc32-iscsi"),
        "unknown fixture flavor '{flavor}'"
    );
    serde_json::from_str::<serde_json::Value>(value)
        .unwrap_or_else(|error| panic!("fixture '{name}' has invalid JSON value: {error}"));

    let hex = bytes
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<Vec<_>>()
        .join(" ");
    println!("{name}\t{flavor}\t{schema}\t{value}\t{hex}\t{source}");
}

fn print_header() {
    println!("# postcard-test-vectors-version: 1");
    println!("# postcard-version: {POSTCARD_VERSION}");
    println!("# upstream-tag: postcard/v{POSTCARD_VERSION}");
    println!("# upstream-commit: {UPSTREAM_COMMIT}");
    println!("name\tflavor\tschema\tvalue\tbytes\tsource");
}

fn print_upstream_vectors() {
    print_upstream_fixture("upstream_unit", "unit", "null", &[], &());
    print_upstream_fixture("upstream_bool_false", "bool", "false", &[0x00], &false);
    print_upstream_fixture("upstream_bool_true", "bool", "true", &[0x01], &true);
    print_upstream_fixture("upstream_u8_5", "u8", "5", &[0x05], &5u8);
    print_upstream_fixture(
        "upstream_u16_42439",
        "u16",
        "42439",
        &[0xc7, 0xcb, 0x02],
        &0xa5c7u16,
    );
    print_upstream_fixture(
        "upstream_u32_3450549266",
        "u32",
        "3450549266",
        &[0x92, 0xe8, 0xac, 0xed, 0x0c],
        &0xcdab3412u32,
    );
    print_upstream_fixture(
        "upstream_u64_1311768467294899695",
        "u64",
        "1311768467294899695",
        &[0xef, 0x9b, 0xaf, 0x85, 0x89, 0xcf, 0x95, 0x9a, 0x12],
        &0x1234_5678_90ab_cdefu64,
    );
    print_upstream_fixture(
        "upstream_i16_max",
        "i16",
        "32767",
        &[0xfe, 0xff, 0x03],
        &i16::MAX,
    );
    print_upstream_fixture(
        "upstream_i16_min",
        "i16",
        "-32768",
        &[0xff, 0xff, 0x03],
        &i16::MIN,
    );
    print_upstream_fixture("upstream_char_z", "char", "\"z\"", &[0x01, 0x7a], &'z');
    print_upstream_fixture(
        "upstream_char_cent",
        "char",
        "\"¢\"",
        &[0x02, 0xc2, 0xa2],
        &'¢',
    );
    print_upstream_fixture(
        "upstream_char_gothic",
        "char",
        "\"𐍈\"",
        &[0x04, 0xf0, 0x90, 0x8d, 0x88],
        &'𐍈',
    );
    print_upstream_fixture(
        "upstream_char_pleading_face",
        "char",
        "\"🥺\"",
        &[0x04, 0xf0, 0x9f, 0xa5, 0xba],
        &'🥺',
    );

    let basic = UpstreamBasic {
        st: 0xabcd,
        ei: 0xfe,
        sf: 0x1234_4321_abcd_dcba,
        tt: 0xacac_acac,
    };
    print_upstream_fixture(
        "upstream_struct_basic",
        "struct Basic{st:u16,ei:u8,sf:u64,tt:u32}",
        "{\"st\":43981,\"ei\":254,\"sf\":1311747203367034042,\"tt\":2896997548}",
        &[
            0xcd, 0xd7, 0x02, 0xfe, 0xba, 0xb9, 0xb7, 0xde, 0x9a, 0xe4, 0x90, 0x9a, 0x12, 0xac,
            0xd9, 0xb2, 0xe5, 0x0a,
        ],
        &basic,
    );
    print_upstream_fixture(
        "upstream_enum_basic_bim",
        "enum BasicEnum{Bib,Bim,Bap}",
        "{\"variant\":\"Bim\"}",
        &[0x01],
        &UpstreamBasicEnum::Bim,
    );
    print_upstream_fixture(
        "upstream_enum_data_bim",
        "enum DataEnum{Bib(u16),Bim(u64),Bap(u8),Kim(struct{eight:u8,sixt:u16}),Chi{a:u8,b:u32},Sho(u16,u8)}",
        "{\"variant\":\"Bim\",\"fields\":[18446744073709551615]}",
        &[0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01],
        &UpstreamDataEnum::Bim(u64::MAX),
    );
    print_upstream_fixture(
        "upstream_enum_data_bib",
        "enum DataEnum{Bib(u16),Bim(u64),Bap(u8),Kim(struct{eight:u8,sixt:u16}),Chi{a:u8,b:u32},Sho(u16,u8)}",
        "{\"variant\":\"Bib\",\"fields\":[65535]}",
        &[0x00, 0xff, 0xff, 0x03],
        &UpstreamDataEnum::Bib(u16::MAX),
    );
    print_upstream_fixture(
        "upstream_enum_data_bap",
        "enum DataEnum{Bib(u16),Bim(u64),Bap(u8),Kim(struct{eight:u8,sixt:u16}),Chi{a:u8,b:u32},Sho(u16,u8)}",
        "{\"variant\":\"Bap\",\"fields\":[255]}",
        &[0x02, 0xff],
        &UpstreamDataEnum::Bap(u8::MAX),
    );
    print_upstream_fixture(
        "upstream_enum_data_kim",
        "enum DataEnum{Bib(u16),Bim(u64),Bap(u8),Kim(struct{eight:u8,sixt:u16}),Chi{a:u8,b:u32},Sho(u16,u8)}",
        "{\"variant\":\"Kim\",\"fields\":[{\"eight\":240,\"sixt\":44204}]}",
        &[0x03, 0xf0, 0xac, 0xd9, 0x02],
        &UpstreamDataEnum::Kim(UpstreamEnumStruct {
            eight: 0xf0,
            sixt: 0xacac,
        }),
    );
    print_upstream_fixture(
        "upstream_enum_data_chi",
        "enum DataEnum{Bib(u16),Bim(u64),Bap(u8),Kim(struct{eight:u8,sixt:u16}),Chi{a:u8,b:u32},Sho(u16,u8)}",
        "{\"variant\":\"Chi\",\"fields\":{\"a\":15,\"b\":3351758791}}",
        &[0x04, 0x0f, 0xc7, 0x8f, 0x9f, 0xbe, 0x0c],
        &UpstreamDataEnum::Chi {
            a: 0x0f,
            b: 0xc7c7_c7c7,
        },
    );
    print_upstream_fixture(
        "upstream_enum_data_sho",
        "enum DataEnum{Bib(u16),Bim(u64),Bap(u8),Kim(struct{eight:u8,sixt:u16}),Chi{a:u8,b:u32},Sho(u16,u8)}",
        "{\"variant\":\"Sho\",\"fields\":[26985,7]}",
        &[0x05, 0xe9, 0xd2, 0x01, 0x07],
        &UpstreamDataEnum::Sho(0x6969, 0x07),
    );
    print_upstream_fixture(
        "upstream_tuple_u8_u16",
        "tuple(u8,u16)",
        "[18,51109]",
        &[0x12, 0xa5, 0x8f, 0x03],
        &(0x12u8, 0xc7a5u16),
    );
    print_upstream_fixture(
        "upstream_newtype_u32",
        "newtype(u32)",
        "5",
        &[0x05],
        &UpstreamNewtype(5),
    );
    print_upstream_fixture(
        "upstream_tuple_struct",
        "tuple-struct(tuple(u8,u16))",
        "[[160,4660]]",
        &[0xa0, 0xb4, 0x24],
        &UpstreamTupleStruct((0xa0, 0x1234)),
    );
    print_upstream_fixture(
        "upstream_seq_u8",
        "seq<u8>",
        "[1,2,3,4]",
        &[0x04, 0x01, 0x02, 0x03, 0x04],
        &vec![1u8, 2, 3, 4],
    );
    print_upstream_fixture(
        "upstream_string",
        "string",
        "\"helLO!\"",
        &[0x06, b'h', b'e', b'l', b'L', b'O', b'!'],
        &"helLO!",
    );
    print_upstream_fixture(
        "upstream_map_u8_u8",
        "map<u8,u8>",
        "[{\"key\":1,\"value\":5},{\"key\":2,\"value\":6},{\"key\":3,\"value\":7},{\"key\":4,\"value\":8}]",
        &[0x04, 0x01, 0x05, 0x02, 0x06, 0x03, 0x07, 0x04, 0x08],
        &BTreeMap::from([(1u8, 5u8), (2, 6), (3, 7), (4, 8)]),
    );
    print_upstream_fixture(
        "upstream_cstring_bytes",
        "bytes",
        "[104,101,76,108,111]",
        &[0x05, b'h', b'e', b'L', b'l', b'o'],
        &std::ffi::CString::new("heLlo").unwrap(),
    );

    print_upstream_cobs_fixture(
        "upstream_cobs_false",
        "bool",
        "false",
        &[0x01, 0x01, 0x00],
        &false,
    );
    print_upstream_cobs_fixture(
        "upstream_cobs_string_1",
        "string",
        "\"1\"",
        &[0x03, 0x01, b'1', 0x00],
        &"1",
    );
    print_upstream_cobs_fixture(
        "upstream_cobs_string_hi",
        "string",
        "\"Hi!\"",
        &[0x05, 0x03, b'H', b'i', b'!', 0x00],
        &"Hi!",
    );
    print_upstream_cobs_fixture(
        "upstream_cobs_bytes",
        "bytes",
        "[1,0,32,48]",
        &[0x03, 0x04, 0x01, 0x03, 0x20, 0x30, 0x00],
        &[0x01u8, 0x00, 0x20, 0x30][..],
    );
    print_upstream_crc_fixture(
        "upstream_crc32c_bytes",
        "bytes",
        "[1,0,32,48]",
        &[0x04, 0x01, 0x00, 0x20, 0x30, 0x8e, 0xc8, 0x1a, 0x37],
        &[0x01u8, 0x00, 0x20, 0x30][..],
    );
}

fn print_postino_vectors() {
    print_fixture("bool_true", "bool", "true", &true);
    print_fixture("byte_minus_one", "i8", "-1", &-1i8);
    print_fixture("u8_255", "u8", "255", &u8::MAX);
    print_fixture("i16_minus_two", "i16", "-2", &-2i16);
    print_fixture("i32_300", "i32", "300", &300i32);
    print_fixture("i64_minus_one", "i64", "-1", &-1i64);
    print_fixture("i64_min", "i64", "-9223372036854775808", &i64::MIN);
    print_fixture("char_e_acute", "char", "\"é\"", &'\u{00e9}');
    print_fixture("i128_300", "i128", "300", &300i128);
    print_fixture("i128_minus_one", "i128", "-1", &-1i128);
    print_fixture(
        "i128_min",
        "i128",
        "-170141183460469231731687303715884105728",
        &i128::MIN,
    );
    print_fixture(
        "u128_340282366920938463463374607431768211455",
        "u128",
        "340282366920938463463374607431768211455",
        &u128::MAX,
    );
    print_fixture("float_1", "f32", "1.0", &1.0f32);
    print_fixture("double_1_5", "f64", "1.5", &1.5f64);
    print_fixture("u16_65535", "u16", "65535", &u16::MAX);
    print_fixture("u32_4294967295", "u32", "4294967295", &u32::MAX);
    print_fixture(
        "u64_18446744073709551615",
        "u64",
        "18446744073709551615",
        &u64::MAX,
    );
    print_fixture("string", "string", "\"postino\"", &"postino");
    print_fixture(
        "bytes",
        "bytes",
        "[222,173,190,239]",
        &vec![0xde_u8, 0xad, 0xbe, 0xef],
    );
    print_fixture("option_i32_none", "option<i32>", "null", &None::<i32>);
    print_fixture("option_i32_some_300", "option<i32>", "300", &Some(300i32));
    print_fixture(
        "option_option_i32_some_none",
        "option<option<i32>>",
        "{\"some\":null}",
        &Some(None::<i32>),
    );
    print_fixture(
        "option_option_i32_some_some_300",
        "option<option<i32>>",
        "{\"some\":{\"some\":300}}",
        &Some(Some(300i32)),
    );
    print_fixture("empty_vec_i16", "seq<i16>", "[]", &Vec::<i16>::new());
    print_fixture("list", "seq<i16>", "[1,-1,300]", &vec![1i16, -1i16, 300i16]);
    print_fixture(
        "map_i32_string",
        "map<i32,string>",
        "[{\"key\":1,\"value\":\"one\"},{\"key\":2,\"value\":\"two\"}]",
        &BTreeMap::from([(1i32, "one"), (2i32, "two")]),
    );

    let sensor = Sensor {
        id: 0x1234,
        temp: -21,
        label: "lab",
    };
    let sensor_schema = "struct Sensor{id:u16,temp:i32,label:string}";
    let sensor_value = "{\"id\":4660,\"temp\":-21,\"label\":\"lab\"}";
    print_fixture("sensor", sensor_schema, sensor_value, &sensor);
    print_cobs_fixture("sensor_cobs", sensor_schema, sensor_value, &sensor);
    print_crc_fixture(
        "sensor_crc32",
        sensor_schema,
        sensor_value,
        &CRC_32_ISO_HDLC,
        &sensor,
    );
    print_fixture(
        "envelope",
        "struct Envelope{sensor:struct Sensor{id:u16,temp:i32,label:string},readings:seq<i16>,note:option<string>,bytes:bytes}",
        "{\"sensor\":{\"id\":7,\"temp\":42,\"label\":\"rack\"},\"readings\":[-1,0,1],\"note\":\"ok\",\"bytes\":[1,2,3]}",
        &Envelope {
            sensor: Sensor {
                id: 7,
                temp: 42,
                label: "rack",
            },
            readings: vec![-1, 0, 1],
            note: Some("ok"),
            bytes: &[1, 2, 3],
        },
    );

    let message_schema = "enum Message{Ping,Pong{id:u16},Data(bytes)}";
    print_fixture(
        "enum_ping",
        message_schema,
        "{\"variant\":\"Ping\"}",
        &Message::Ping,
    );
    print_fixture(
        "enum_pong",
        message_schema,
        "{\"variant\":\"Pong\",\"fields\":{\"id\":43981}}",
        &Message::Pong { id: 0xabcd },
    );
    print_fixture(
        "enum_data",
        message_schema,
        "{\"variant\":\"Data\",\"fields\":[[9,8,7]]}",
        &Message::Data(&[9, 8, 7]),
    );

    let derived_schema = "enum DerivedMessage{Ping,Pong{id:u16},Data(bytes)}";
    print_fixture(
        "derived_enum_ping",
        derived_schema,
        "{\"variant\":\"Ping\"}",
        &DerivedMessage::Ping,
    );
    print_fixture(
        "derived_enum_pong",
        derived_schema,
        "{\"variant\":\"Pong\",\"fields\":{\"id\":43981}}",
        &DerivedMessage::Pong { id: 0xabcd },
    );
    print_fixture(
        "derived_enum_data",
        derived_schema,
        "{\"variant\":\"Data\",\"fields\":[[9,8,7]]}",
        &DerivedMessage::Data(&[9, 8, 7]),
    );
    print_fixture(
        "enum_discriminant_128",
        "enum WideMessage{128:High}",
        "{\"variant\":\"High\"}",
        &WideMessage::High,
    );
}

fn main() {
    print_header();
    print_upstream_vectors();
    print_postino_vectors();
}
