# Dịch Nga ⇄ Việt — phiên dịch giọng nói offline, miễn phí

## App làm được gì
- Bấm **🎤 Nói tiếng Việt** → nói → bấm **⏹ Dừng** → app hiện chữ, dịch sang tiếng Nga và **đọc to** bằng tiếng Nga.
- Bấm **🎤 Говорить по-русски** → người Nga nói → bấm **Стоп** → dịch sang tiếng Việt và đọc to.
- Có ô gõ chữ để dịch thủ công.
- Sau khi tải dữ liệu 1 lần (~140 MB), **dùng hoàn toàn không cần mạng**.

Thư viện dùng (đều miễn phí): Vosk (nhận giọng nói offline), Google ML Kit Translation (dịch offline), TextToSpeech có sẵn trong Android (đọc).

---

## Cách 1 — Build ra file APK miễn phí bằng GitHub (không cần cài gì)
1. Tạo tài khoản tại https://github.com (miễn phí).
2. Bấm **New repository** → đặt tên `DichNgaViet` → **Create repository**.
3. Bấm **uploading an existing file** → kéo **toàn bộ nội dung** đã giải nén (các thư mục `app`, `.github`, file `build.gradle`, `settings.gradle`, `gradle.properties`) vào → **Commit changes**.
   - Lưu ý: thư mục `.github` là thư mục ẩn, trên Mac/Windows có thể không kéo được. Nếu vậy: bấm **Add file → Create new file**, gõ tên `.github/workflows/build-apk.yml`, rồi dán nội dung file đó vào → Commit.
4. Vào tab **Actions** → chờ dòng **Build APK** chuyển màu xanh ✅ (khoảng 5–8 phút).
5. Bấm vào lần build đó → kéo xuống mục **Artifacts** → tải **DichNgaViet-apk** (file zip) → giải nén ra **DichNgaViet.apk**.
6. Chép APK vào điện thoại → mở để cài (cho phép "Cài ứng dụng không rõ nguồn gốc").

## Cách 2 — Android Studio
Mở thư mục này bằng Android Studio → chờ đồng bộ Gradle → **Build → Build APK(s)**.

---

## Lần đầu sử dụng trên điện thoại (cần Wi-Fi 1 lần)
1. Mở app, cho phép **micro**.
2. Bấm **⬇ Tải dữ liệu offline** → chờ đến khi thấy **✅ Sẵn sàng (offline)**.
3. Bấm **🔊 Giọng đọc** → chọn *Dịch vụ lời nói của Google* → ⚙ → **Cài đặt dữ liệu giọng nói** → tải **Tiếng Việt** và **Русский (Tiếng Nga)**. (Không tải thì app vẫn dịch, nhưng không đọc to được khi mất mạng.)
4. Tắt mạng thử — app vẫn hoạt động.

## Mẹo dùng
- Nói từng câu ngắn, rõ, gần micro; nói xong mới bấm Dừng.
- Mô hình giọng nói tiếng Việt bản nhỏ cho ra chữ không dấu câu, đôi khi nhầm từ — nếu sai có thể gõ lại vào ô chữ.
- Bản dịch offline đủ dùng cho giao tiếp hằng ngày, không chính xác bằng Google Dịch online.

Yêu cầu: Android 7.0 trở lên, còn trống ~400 MB.
