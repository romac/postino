use crc::{Crc, CRC_32_ISO_HDLC};
use serde::{Serialize, Serializer};
use std::collections::BTreeMap;

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

fn print_fixture<T: Serialize>(name: &str, value: &T) {
    let bytes = postcard::to_stdvec(value).unwrap();
    print_bytes(name, &bytes);
}

fn print_cobs_fixture<T: Serialize>(name: &str, value: &T) {
    let bytes = postcard::to_stdvec_cobs(value).unwrap();
    print_bytes(name, &bytes);
}

fn print_crc_fixture<T: Serialize>(name: &str, value: &T) {
    let crc = Crc::<u32>::new(&CRC_32_ISO_HDLC);
    let bytes = postcard::to_stdvec_crc32(value, crc.digest()).unwrap();
    print_bytes(name, &bytes);
}

fn print_bytes(name: &str, bytes: &[u8]) {
    let hex = bytes
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<Vec<_>>()
        .join(" ");
    println!("{name}: {hex}");
}

fn main() {
    print_fixture("bool_true", &true);
    print_fixture("byte_minus_one", &-1i8);
    print_fixture("u8_255", &u8::MAX);
    print_fixture("i16_minus_two", &-2i16);
    print_fixture("i32_300", &300i32);
    print_fixture("i64_minus_one", &-1i64);
    print_fixture("i64_min", &i64::MIN);
    print_fixture("char_e_acute", &'\u{00e9}');
    print_fixture("i128_300", &300i128);
    print_fixture("i128_minus_one", &-1i128);
    print_fixture("i128_min", &i128::MIN);
    print_fixture("u128_340282366920938463463374607431768211455", &u128::MAX);
    print_fixture("float_1", &1.0f32);
    print_fixture("double_1_5", &1.5f64);
    print_fixture("u16_65535", &u16::MAX);
    print_fixture("u32_4294967295", &u32::MAX);
    print_fixture("u64_18446744073709551615", &u64::MAX);
    print_fixture("string", &"postino");
    print_fixture("bytes", &vec![0xde_u8, 0xad, 0xbe, 0xef]);
    print_fixture("option_i32_none", &None::<i32>);
    print_fixture("option_i32_some_300", &Some(300i32));
    print_fixture("option_option_i32_some_none", &Some(None::<i32>));
    print_fixture("option_option_i32_some_some_300", &Some(Some(300i32)));
    print_fixture("empty_vec_i16", &Vec::<i16>::new());
    print_fixture("list", &vec![1i16, -1i16, 300i16]);
    print_fixture(
        "map_i32_string",
        &BTreeMap::from([(1i32, "one"), (2i32, "two")]),
    );
    print_fixture(
        "sensor",
        &Sensor {
            id: 0x1234,
            temp: -21,
            label: "lab",
        },
    );
    print_cobs_fixture(
        "sensor_cobs",
        &Sensor {
            id: 0x1234,
            temp: -21,
            label: "lab",
        },
    );
    print_crc_fixture(
        "sensor_crc32",
        &Sensor {
            id: 0x1234,
            temp: -21,
            label: "lab",
        },
    );
    print_fixture(
        "envelope",
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
    print_fixture("enum_ping", &Message::Ping);
    print_fixture("enum_pong", &Message::Pong { id: 0xabcd });
    print_fixture("enum_data", &Message::Data(&[9, 8, 7]));
    print_fixture("derived_enum_ping", &DerivedMessage::Ping);
    print_fixture("derived_enum_pong", &DerivedMessage::Pong { id: 0xabcd });
    print_fixture("derived_enum_data", &DerivedMessage::Data(&[9, 8, 7]));
    print_fixture("enum_discriminant_128", &WideMessage::High);
}
