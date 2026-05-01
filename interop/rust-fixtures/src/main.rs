use serde::Serialize;

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

fn print_fixture<T: Serialize>(name: &str, value: &T) {
    let bytes = postcard::to_stdvec(value).unwrap();
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
    print_fixture("i16_minus_two", &-2i16);
    print_fixture("i32_300", &300i32);
    print_fixture("float_1", &1.0f32);
    print_fixture("double_1_5", &1.5f64);
    print_fixture("u16_65535", &u16::MAX);
    print_fixture("u32_4294967295", &u32::MAX);
    print_fixture("u64_18446744073709551615", &u64::MAX);
    print_fixture("string", &"postino");
    print_fixture("bytes", &vec![0xde_u8, 0xad, 0xbe, 0xef]);
    print_fixture("option_i32_none", &None::<i32>);
    print_fixture("option_i32_some_300", &Some(300i32));
    print_fixture("list", &vec![1i16, -1i16, 300i16]);
    print_fixture(
        "sensor",
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
}
