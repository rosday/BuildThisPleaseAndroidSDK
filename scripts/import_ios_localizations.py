#!/usr/bin/env python3
"""Import the shared BuildThisPlease copy from the iOS string catalogs.

Usage: scripts/import_ios_localizations.py ../BuildThisPleaseSDK
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


LOCALE_DIRECTORIES = {
    "ar": "values-ar", "cs": "values-cs", "da": "values-da", "de": "values-de",
    "el": "values-el", "es": "values-es", "fi": "values-fi", "fil": "values-fil",
    "fr": "values-fr", "he": "values-he", "hr": "values-hr", "hu": "values-hu",
    "id": "values-id", "it": "values-it", "ja": "values-ja", "ko": "values-ko",
    "ms": "values-ms", "nb": "values-nb", "nl": "values-nl", "pl": "values-pl",
    "pt-BR": "values-pt-rBR", "pt-PT": "values-pt-rPT", "ro": "values-ro",
    "ru": "values-ru", "sk": "values-sk", "sv": "values-sv", "th": "values-th",
    "tr": "values-tr", "uk": "values-uk", "vi": "values-vi",
    "zh-Hans": "values-b+zh+Hans", "zh-Hant": "values-b+zh+Hant",
}

ANDROID_ONLY = {
    "btp_back": {
        "ar": "رجوع", "cs": "Zpět", "da": "Tilbage", "de": "Zurück", "el": "Πίσω",
        "es": "Atrás", "fi": "Takaisin", "fil": "Bumalik", "fr": "Retour", "he": "חזרה",
        "hr": "Natrag", "hu": "Vissza", "id": "Kembali", "it": "Indietro", "ja": "戻る",
        "ko": "뒤로", "ms": "Kembali", "nb": "Tilbake", "nl": "Terug", "pl": "Wstecz",
        "pt-BR": "Voltar", "pt-PT": "Voltar", "ro": "Înapoi", "ru": "Назад", "sk": "Späť",
        "sv": "Tillbaka", "th": "ย้อนกลับ", "tr": "Geri", "uk": "Назад", "vi": "Quay lại",
        "zh-Hans": "返回", "zh-Hant": "返回",
    },
    "btp_other_user": {
        "ar": "مستخدم", "cs": "Uživatel", "da": "Bruger", "de": "Benutzer", "el": "Χρήστης",
        "es": "Usuario", "fi": "Käyttäjä", "fil": "User", "fr": "Utilisateur", "he": "משתמש",
        "hr": "Korisnik", "hu": "Felhasználó", "id": "Pengguna", "it": "Utente", "ja": "ユーザー",
        "ko": "사용자", "ms": "Pengguna", "nb": "Bruker", "nl": "Gebruiker", "pl": "Użytkownik",
        "pt-BR": "Usuário", "pt-PT": "Utilizador", "ro": "Utilizator", "ru": "Пользователь",
        "sk": "Používateľ", "sv": "Användare", "th": "ผู้ใช้", "tr": "Kullanıcı",
        "uk": "Користувач", "vi": "Người dùng", "zh-Hans": "用户", "zh-Hant": "使用者",
    },
    "btp_invalid_email": {
        "ar": "أدخل عنوان بريد إلكتروني صالحًا", "cs": "Zadejte platnou e-mailovou adresu",
        "da": "Indtast en gyldig e-mailadresse", "de": "Gib eine gültige E-Mail-Adresse ein",
        "el": "Εισαγάγετε μια έγκυρη διεύθυνση email", "es": "Introduce una dirección de correo electrónico válida",
        "fi": "Anna kelvollinen sähköpostiosoite", "fil": "Maglagay ng valid na email address",
        "fr": "Saisissez une adresse e-mail valide", "he": "יש להזין כתובת אימייל חוקית",
        "hr": "Unesite valjanu adresu e-pošte", "hu": "Adj meg egy érvényes e-mail-címet",
        "id": "Masukkan alamat email yang valid", "it": "Inserisci un indirizzo email valido",
        "ja": "有効なメールアドレスを入力してください", "ko": "올바른 이메일 주소를 입력하세요",
        "ms": "Masukkan alamat e-mel yang sah", "nb": "Skriv inn en gyldig e-postadresse",
        "nl": "Voer een geldig e-mailadres in", "pl": "Wpisz prawidłowy adres e-mail",
        "pt-BR": "Insira um endereço de e-mail válido", "pt-PT": "Introduza um endereço de e-mail válido",
        "ro": "Introdu o adresă de e-mail validă", "ru": "Введите действительный адрес электронной почты",
        "sk": "Zadajte platnú e-mailovú adresu", "sv": "Ange en giltig e-postadress",
        "th": "ป้อนที่อยู่อีเมลที่ถูกต้อง", "tr": "Geçerli bir e-posta adresi girin",
        "uk": "Введіть дійсну адресу електронної пошти", "vi": "Nhập địa chỉ email hợp lệ",
        "zh-Hans": "请输入有效的电子邮件地址", "zh-Hant": "請輸入有效的電子郵件地址",
    },
}


def catalog(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)["strings"]


def localized_value(catalogs: list[dict], source: str, locale: str) -> str | None:
    for strings in catalogs:
        unit = strings.get(source, {}).get("localizations", {}).get(locale, {}).get("stringUnit")
        if unit and unit.get("state") == "translated":
            return unit.get("value")
    return None


def android_text(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("ios_sdk", type=Path)
    args = parser.parse_args()

    ios = args.ios_sdk.resolve()
    catalogs = [
        catalog(ios / "Sources/BuildThisPleaseUI/Resources/Localizable.xcstrings"),
        catalog(ios / "Sources/BuildThisPleaseCore/Resources/Localizable.xcstrings"),
    ]
    res = Path(__file__).resolve().parents[1] / "buildthisplease-compose/src/main/res"
    base = ET.parse(res / "values/strings.xml").getroot()

    for locale, directory in LOCALE_DIRECTORIES.items():
        root = ET.Element("resources")
        for item in base.findall("string"):
            if item.get("translatable") == "false":
                continue
            name = item.attrib["name"]
            source = item.text or ""
            value = ANDROID_ONLY.get(name, {}).get(locale) or localized_value(catalogs, source, locale)
            if value is None:
                raise RuntimeError(f"Missing {locale} translation for {name}: {source!r}")
            translated = ET.SubElement(root, "string", {"name": name})
            translated.text = android_text(value)

        ET.indent(root, space="    ")
        destination = res / directory / "strings.xml"
        destination.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(root).write(destination, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
