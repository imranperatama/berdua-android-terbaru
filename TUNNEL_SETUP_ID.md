# Setup alamat ngrok otomatis

Aplikasi membaca `https://raw.githubusercontent.com/imranperatama/nexora-android-terbaru/main/tunnel.json` untuk menemukan URL tunnel saat HP kedua menghubungkan kembali. Helper yang disertakan berada di `tools/termux-ngrok/`.

## Yang diperlukan satu kali

- Repositori `imranperatama/nexora-android-terbaru` pada branch `main` tetap tersedia.
- Akun ngrok dan authtoken dari dashboard ngrok.
- GitHub fine-grained token khusus repositori dengan **Contents: Read and write**.
- Termux yang sesuai untuk perangkat dan koneksi internet yang stabil.

Jalankan `bash setup.sh` dari direktori helper di Termux dan masukkan token hanya pada prompt privat. Jangan unggah file `~/.config/nexora/ngrok.env` atau konfigurasi ngrok ke GitHub.

Helper memeriksa `http://127.0.0.1:8787/api/discovery-health`, memantau API lokal ngrok di port 4040, dan mengubah `tunnel.json` melalui GitHub API. HP kedua cukup mengosongkan kolom URL agar penemuan otomatis bisa dipakai.

Domain ngrok yang ditetapkan ke akun biasanya bertahan saat agent restart. Klaim/pilih development domain di dashboard ngrok satu kali jika akun memintanya. Pembatasan paket/kuota ngrok masih berlaku.

Jika server HP pertama mati atau aplikasi menutup server, tidak ada server yang tersedia; helper akan menerbitkan status offline. Android tetap bisa menghentikan aplikasi latar belakang. Setup Termux:Boot dan pengaturan baterai dapat membantu, tetapi tidak menjamin semua perangkat mempertahankan proses.
