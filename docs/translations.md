# Translations

GlobalFitness follows the phone's language (and the per-app language in Android 13+:
*Settings → Apps → GlobalFitness → Language*, also reachable from *Profile → Language*). English is the
default for any language without a translation.

| Language | Android resources | Play Store listing |
|---|---|---|
| English (default) | `values` | `docs/store-listing/en-US.md` |
| Spanish | `values-es` | `docs/store-listing/es-ES.md` |
| Portuguese (Brazil) | `values-pt-rBR` | `docs/store-listing/pt-BR.md` |
| Portuguese (Portugal) | `values-pt-rPT` | `docs/store-listing/pt-PT.md` |
| French | `values-fr` | `docs/store-listing/fr-FR.md` |
| German | `values-de` | `docs/store-listing/de-DE.md` |
| Italian | `values-it` | `docs/store-listing/it-IT.md` |
| Russian | `values-ru` | `docs/store-listing/ru-RU.md` |
| Chinese (Simplified) | `values-zh-rCN` | `docs/store-listing/zh-CN.md` |
| Japanese | `values-ja` | `docs/store-listing/ja-JP.md` |
| Korean | `values-ko` | `docs/store-listing/ko-KR.md` |
| Turkish | `values-tr` | `docs/store-listing/tr-TR.md` |
| Polish | `values-pl` | `docs/store-listing/pl-PL.md` |
| Hindi | `values-hi` | `docs/store-listing/hi-IN.md` |
| Arabic (right-to-left) | `values-ar` | `docs/store-listing/ar.md` |
| Indonesian | `values-in` | `docs/store-listing/id.md` |
| Vietnamese | `values-vi` | `docs/store-listing/vi.md` |

## Review by native speakers is recommended

English and Spanish are the reference texts. The other 15 languages were translated with an AI model
and checked automatically (every key present, placeholders and plural forms intact, Play limits), but
**no native speaker has reviewed them yet**. Before relying on them, and especially before publishing the
store listings, please have a native speaker check:

- the body-composition and health terms (glossary below) against what fitness apps use in that language;
- the cautious medical wording (estimates, "not a medical device");
- texts that quote other screens' labels (they must match those labels exactly);
- short labels in the bottom bar, chips and buttons, on a small phone.

Corrections go into `tools/i18n/src/<locale>/*.txt`; see `tools/i18n/TRANSLATING.md` for the format, the
plural categories of each language and how to regenerate and check the resources
(`python tools/i18n/gen.py app/src/main/res --check`). Store listings are checked with
`python tools/i18n/check_listing.py`.

## What stays untranslated on purpose

- Brand, model and protocol names (Xiaomi, Mi Body Composition Scale 2, Health Connect, Bluetooth LE,
  GATT, MGB…), and Xiaomi's own term "bind key".
- The band's connection log (*Devices → Band → Open direct mode*): it stays in English so bug reports
  can be read by the developers; the screen says so in the user's language.
- The privacy policy exists in Spanish (`docs/privacy-policy.md`, the legal reference) and English
  (`docs/privacy-policy.en.md`). The app shows the English copy in every language other than Spanish;
  the web version (`hosting/public/privacy.html`) is Spanish with an English summary.

## Glossary

The terms each translation uses for the key concepts, as they appear in the app (taken from the
sources). Keep them consistent when editing.

| English | es | pt-BR | pt-PT | fr | de | it |
|---|---|---|---|---|---|---|
| Weight | Peso | Peso | Peso | Poids | Gewicht | Peso |
| BMI | IMC | IMC | IMC | IMC | BMI | BMI |
| Body composition | Composición corporal | Composição corporal | Composição corporal | Composition corporelle | Körperzusammensetzung | Composizione corporea |
| Body type | Tipo de cuerpo | Tipo corporal | Tipo de corpo | Morphologie | Körpertyp | Tipo di fisico |
| Body fat | Grasa | Gordura | Gordura corporal | Graisse corporelle | Körperfett | Grasso corporeo |
| Fat mass | Masa grasa | Massa gorda | Massa gorda | Masse grasse | Fettmasse | Massa grassa |
| Visceral fat | Grasa visceral | Gordura visceral | Gordura visceral | Graisse viscérale | Viszeralfett | Grasso viscerale |
| Muscle | Músculo | Massa muscular | Massa muscular | Masse musculaire | Muskelmasse | Massa muscolare |
| Skeletal muscle | Músculo esquelético | Músculo esquelético | Músculo esquelético | Muscle squelettique | Skelettmuskelmasse | Muscolo scheletrico |
| Water | Agua | Água | Água corporal | Eau corporelle | Körperwasser | Acqua corporea |
| Bone | Hueso | Massa óssea | Massa óssea | Masse osseuse | Knochenmasse | Massa ossea |
| Protein | Proteína | Proteína | Proteína | Protéines | Protein | Proteine |
| Basal metabolic rate | Metabolismo basal | Taxa metabólica basal | Metabolismo basal | Métabolisme de base | Grundumsatz | Metabolismo basale |
| Metabolic age | Edad metabólica | Idade metabólica | Idade metabólica | Âge métabolique | Stoffwechselalter | Età metabolica |
| Ideal weight | Peso ideal | Peso ideal | Peso ideal | Poids idéal | Idealgewicht | Peso ideale |
| Impedance | Impedancia | Impedância | Impedância | Impédance | Impedanz | Impedenza |
| Scale | Báscula | Balança | Balança | Balance | Waage | Bilancia |
| Band | Pulsera | Pulseira | Pulseira | Bracelet | Band | Smartband |
| Measurements | Mediciones | Medições | Medições | Mesures | Messungen | Misurazioni |
| Steps | Pasos | Passos | Passos | Pas | Schritte | Passi |
| Sleep | Sueño | Sono | Sono | Sommeil | Schlaf | Sonno |
| Light | Ligero | Leve | Leve | Léger | Leichtschlaf | Leggero |
| Deep | Profundo | Profundo | Profundo | Profond | Tiefschlaf | Profondo |
| Heart rate | Pulso | Frequência cardíaca | Frequência cardíaca | Fréquence cardiaque | Herzfrequenz | Frequenza cardiaca |
| Resting heart rate | Pulso en reposo | Frequência cardíaca em repouso | Frequência cardíaca em repouso | Fréquence cardiaque au repos | Ruhepuls | Frequenza cardiaca a riposo |
| Heart rate variability | Variabilidad del pulso | Variabilidade da frequência cardíaca | Variabilidade da frequência cardíaca | Variabilité de la fréquence cardiaque | Herzfrequenzvariabilität | Variabilità della frequenza cardiaca |
| Blood oxygen | Oxígeno en sangre | Oxigênio no sangue | Oxigénio no sangue | Oxygène sanguin | Blutsauerstoff | Ossigeno nel sangue |
| Respiratory rate | Frecuencia respiratoria | Frequência respiratória | Frequência respiratória | Fréquence respiratoire | Atemfrequenz | Frequenza respiratoria |
| Cloud backup | Copia de seguridad en la nube | Backup na nuvem | Cópia de segurança na nuvem | Sauvegarde dans le cloud | Cloud-Backup | Backup sul cloud |
| Sync now | Sincronizar ahora | Sincronizar agora | Sincronizar agora | Synchroniser maintenant | Jetzt synchronisieren | Sincronizza ora |
| Direct mode | Modo directo | Modo direto | Modo direto | Mode direct | Direktmodus | Modalità diretta |
| Authentication key | Clave de autenticación | Chave de autenticação | Chave de autenticação | Clé d'authentification | Authentifizierungsschlüssel | Chiave di autenticazione |
| broadcast | anuncios | transmissão | difusão | diffusion | Broadcast | broadcast |
| Remove ads | Quitar anuncios | Remover anúncios | Remover anúncios | Supprimer les publicités | Werbung entfernen | Rimuovi annunci |
| Experimental | Experimental | Experimental | Experimental | Expérimental | Experimentell | Sperimentale |

| English | ru | pl | tr | hi | ar |
|---|---|---|---|---|---|
| Weight | Вес | Waga | Kilo | वज़न | الوزن |
| BMI | ИМТ | BMI | VKİ | BMI | مؤشر كتلة الجسم |
| Body composition | Состав тела | Skład ciała | Vücut kompozisyonu | शारीरिक संरचना | تكوين الجسم |
| Body type | Тип телосложения | Typ sylwetki | Vücut tipi | शरीर का प्रकार | نوع الجسم |
| Body fat | Процент жира | Tkanka tłuszczowa | Vücut yağı | शरीर में वसा | دهون الجسم |
| Fat mass | Жировая масса | Masa tłuszczowa | Yağ kütlesi | वसा द्रव्यमान | كتلة الدهون |
| Visceral fat | Висцеральный жир | Tłuszcz trzewny | Viseral yağ | आंत की वसा | الدهون الحشوية |
| Muscle | Мышечная масса | Masa mięśniowa | Kas | मांसपेशी | العضلات |
| Skeletal muscle | Скелетные мышцы | Mięśnie szkieletowe | İskelet kası | कंकाल मांसपेशी | العضلات الهيكلية |
| Water | Вода | Woda | Su | पानी | ماء الجسم |
| Bone | Костная масса | Masa kostna | Kemik | हड्डी | كتلة العظام |
| Protein | Белок | Białko | Protein | प्रोटीन | البروتين |
| Basal metabolic rate | Базальный метаболизм | Podstawowa przemiana materii | Bazal metabolizma hızı | बेसल मेटाबॉलिक रेट | معدل الأيض الأساسي |
| Metabolic age | Метаболический возраст | Wiek metaboliczny | Metabolik yaş | मेटाबॉलिक आयु | العمر الأيضي |
| Ideal weight | Идеальный вес | Idealna waga | İdeal kilo | आदर्श वज़न | الوزن المثالي |
| Impedance | Импеданс | Impedancja | Empedans | इम्पीडेंस | المعاوقة |
| Scale | Весы | Waga | Tartı | स्केल | الميزان |
| Band | Браслет | Opaska | Bileklik | बैंड | السوار |
| Measurements | Измерения | Pomiary | Ölçümler | माप | القياسات |
| Steps | Шаги | Kroki | Adımlar | कदम | الخطوات |
| Sleep | Сон | Sen | Uyku | नींद | النوم |
| Light | Лёгкий | Płytki | Hafif | हल्की | خفيف |
| Deep | Глубокий | Głęboki | Derin | गहरी | عميق |
| Heart rate | Пульс | Tętno | Nabız | हृदय गति | معدل ضربات القلب |
| Resting heart rate | Пульс в покое | Tętno spoczynkowe | Dinlenme nabzı | आराम की हृदय गति | معدل ضربات القلب أثناء الراحة |
| Heart rate variability | Вариабельность сердечного ритма | Zmienność rytmu serca | Nabız değişkenliği | हृदय गति परिवर्तनशीलता | تقلب معدل ضربات القلب |
| Blood oxygen | Кислород в крови | Tlen we krwi | Kan oksijeni | रक्त ऑक्सीजन | الأكسجين في الدم |
| Respiratory rate | Частота дыхания | Częstość oddechów | Solunum hızı | श्वसन दर | معدل التنفس |
| Cloud backup | Резервная копия в облаке | Kopia zapasowa w chmurze | Bulut yedeği | क्लाउड बैकअप | النسخ الاحتياطي السحابي |
| Sync now | Синхронизировать | Synchronizuj teraz | Şimdi senkronize et | अभी सिंक करें | مزامنة الآن |
| Direct mode | Прямой режим | Tryb bezpośredni | Doğrudan mod | डायरेक्ट मोड | الوضع المباشر |
| Authentication key | Ключ аутентификации | Klucz uwierzytelniania | Kimlik doğrulama anahtarı | प्रमाणीकरण कुंजी | مفتاح المصادقة |
| broadcast | широковещание | rozgłaszanie | yayın | ब्रॉडकास्ट | بث |
| Remove ads | Отключить рекламу | Usuń reklamy | Reklamları kaldır | विज्ञापन हटाएं | إزالة الإعلانات |
| Experimental | Экспериментально | Eksperymentalne | Deneysel | प्रायोगिक | تجريبي |

| English | zh-CN | ja | ko | id | vi |
|---|---|---|---|---|---|
| Weight | 体重 | 体重 | 체중 | Berat badan | Cân nặng |
| BMI | BMI | BMI | BMI | IMT | BMI |
| Body composition | 身体成分 | 体組成 | 체성분 | Komposisi tubuh | Thành phần cơ thể |
| Body type | 体型 | 体型 | 체형 | Tipe tubuh | Kiểu cơ thể |
| Body fat | 体脂率 | 体脂肪率 | 체지방률 | Lemak tubuh | Mỡ cơ thể |
| Fat mass | 脂肪量 | 脂肪量 | 체지방량 | Massa lemak | Khối lượng mỡ |
| Visceral fat | 内脏脂肪 | 内臓脂肪 | 내장지방 | Lemak viseral | Mỡ nội tạng |
| Muscle | 肌肉量 | 筋肉量 | 근육량 | Massa otot | Khối lượng cơ |
| Skeletal muscle | 骨骼肌 | 骨格筋率 | 골격근 | Otot rangka | Cơ xương |
| Water | 水分 | 体水分率 | 체수분 | Air tubuh | Nước trong cơ thể |
| Bone | 骨量 | 骨量 | 골량 | Massa tulang | Khối lượng xương |
| Protein | 蛋白质 | タンパク質 | 단백질 | Protein | Protein |
| Basal metabolic rate | 基础代谢 | 基礎代謝 | 기초대사량 | Metabolisme basal | Trao đổi chất cơ bản |
| Metabolic age | 代谢年龄 | 体内年齢 | 대사 연령 | Usia metabolik | Tuổi trao đổi chất |
| Ideal weight | 理想体重 | 理想体重 | 적정 체중 | Berat badan ideal | Cân nặng lý tưởng |
| Impedance | 阻抗 | インピーダンス | 임피던스 | Impedansi | Trở kháng |
| Scale | 体重秤 | 体重計 | 체중계 | Timbangan | Cân |
| Band | 手环 | バンド | 밴드 | Gelang | Vòng đeo tay |
| Measurements | 测量记录 | 測定データ | 측정값 | Pengukuran | Kết quả đo |
| Steps | 步数 | 歩数 | 걸음 수 | Langkah | Số bước |
| Sleep | 睡眠 | 睡眠 | 수면 | Tidur | Giấc ngủ |
| Light | 浅睡 | 浅い睡眠 | 얕은 수면 | Ringan | Ngủ nông |
| Deep | 深睡 | 深い睡眠 | 깊은 수면 | Nyenyak | Ngủ sâu |
| Heart rate | 心率 | 心拍数 | 심박수 | Detak jantung | Nhịp tim |
| Resting heart rate | 静息心率 | 安静時心拍数 | 안정 시 심박수 | Detak jantung istirahat | Nhịp tim lúc nghỉ |
| Heart rate variability | 心率变异性 | 心拍変動 | 심박 변이도 | Variabilitas detak jantung | Biến thiên nhịp tim |
| Blood oxygen | 血氧 | 血中酸素 | 혈중 산소 | Oksigen darah | Oxy trong máu |
| Respiratory rate | 呼吸频率 | 呼吸数 | 호흡수 | Laju pernapasan | Nhịp thở |
| Cloud backup | 云备份 | クラウドバックアップ | 클라우드 백업 | Cadangan cloud | Sao lưu đám mây |
| Sync now | 立即同步 | 今すぐ同期 | 지금 동기화 | Sinkronkan sekarang | Đồng bộ hóa ngay |
| Direct mode | 直连模式 | ダイレクトモード | 직접 연결 모드 | Mode langsung | Chế độ trực tiếp |
| Authentication key | 认证密钥 | 認証キー | 인증 키 | Kunci autentikasi | Khóa xác thực |
| broadcast | 广播 | ブロードキャスト | 브로드캐스트 | siaran | quảng bá |
| Remove ads | 移除广告 | 広告を削除 | 광고 제거 | Hapus iklan | Xóa quảng cáo |
| Experimental | 实验性 | 試験運用版 | 실험 단계 | Eksperimental | Thử nghiệm |

## Points for reviewers

Choices the translators flagged as worth a second look:

- **All languages:** Android menu paths (*Settings → Apps → Mi Fitness → Force stop*) use stock Android
  wording; manufacturer skins may differ. Texts that tell the user to tap "Connect" quote the label of
  the band and onboarding button (`onboarding_connect`).
- **Store titles** are at or near the 30-character limit in several languages (es, pt, fr, ru, tr), so any
  edit needs a recount (`python tools/i18n/check_listing.py`). German, Italian and Indonesian titles had
  to drop "health" to fit.
- **fr:** no space before the colon in the store title (length); "Supprimer les publicités" for
  "Remove ads".
- **de:** "Akku" (band) versus "Batterie" (scale); "Broadcast" for Bluetooth advertisements.
- **it:** the store short description says "battito" instead of "frequenza cardiaca" (length).
- **ru / pl:** several texts are rephrased ("Label: %1$s") to avoid wrong grammatical case after a number.
- **tr:** suffixes are never attached to placeholders (vowel harmony depends on the value).
- **hi:** loanwords such as रीस्टोर and सिमुलेट may be replaced by native words.
- **ar:** menu paths use "←" because "→" points backwards in right-to-left text; the time ranges stay
  "1M 3M 6M 1Y".
- **ja / ko:** "7-day moving average" in the store listing versus "7-day average" in the app.
