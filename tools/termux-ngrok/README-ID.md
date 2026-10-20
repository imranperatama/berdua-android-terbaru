# NEXORA — HP pertama + ngrok auto-discovery (Termux)

Paket ini adalah **helper tunnel**, bukan aplikasi Android. Server chat tetap berada di dalam aplikasi NEXORA di HP pertama pada port `8787`. Helper ini menunggu server aktif, menjalankan ngrok, mendeteksi alamat HTTPS dari API lokal ngrok, lalu memperbarui `tunnel.json` di GitHub. HP kedua membaca alamat tersebut otomatis; domain tidak perlu diketik ulang setiap kali helper memulai ngrok.

## Perlu disiapkan satu kali
- Aplikasi NEXORA terpasang di HP pertama.
- Termux dan koneksi internet yang stabil.
- Akun ngrok serta authtoken dari dashboard ngrok.
- Fine-grained GitHub token dengan **Contents: Read and write** khusus repo `imranperatama/nexora-android-terbaru`. Jangan memakai token penuh akun bila token terbatas bisa dibuat.
- Repo GitHub tersebut harus tetap ada dan branch `main` harus menjadi branch discovery yang digunakan aplikasi.

## Langkah
1. Ekstrak seluruh isi ZIP ke satu folder yang bisa dibuka Termux.
2. Di Termux, masuk ke folder itu dan jalankan `bash setup.sh`.
3. Masukkan authtoken ngrok dan GitHub token ketika diminta. Keduanya disimpan lokal dengan izin file ketat; tidak dimasukkan ke APK atau source code.
4. Buka NEXORA di HP pertama, pilih **Jadikan HP pertama · Server**.
5. Biarkan helper berjalan. Tunggu log menunjukkan `discovery updated: online=true`.
6. Di HP kedua, buka NEXORA → Sambungkan HP kedua. Biarkan URL kosong, masukkan kode pasangan HP pertama.

## Yang otomatis
- Helper memeriksa server lokal `127.0.0.1:8787`.
- Jika server aktif, helper memulai ngrok dan menemukan URL HTTPS secara otomatis dari `127.0.0.1:4040/api/tunnels`.
- `tunnel.json` akan berisi URL dan status online. Saat server tidak aktif, helper menerbitkan status offline; ketika server kembali aktif, URL diterbitkan lagi.
- Ngrok free biasanya menetapkan development domain ke akun. Domain tersebut dirancang agar tidak berubah setiap kali agent restart, selama memakai domain akun yang sama. Jika ngrok meminta domain untuk diklaim, lakukan sekali di dashboard ngrok.

## Otomatis setelah restart HP
Pasang aplikasi Termux:Boot secara terpisah dari sumber resminya, buka sekali, dan izinkan Termux berjalan di latar belakang. Setup membuat file boot untuk memulai watcher setelah restart. Android tetap dapat mematikan proses karena penghematan baterai; set unrestricted battery/background usage untuk Termux dan NEXORA saat uji coba.

## Troubleshooting
- **ngrok binary tidak dapat dijalankan di Termux:** installer mencoba biner resmi Linux sesuai arsitektur, tetapi kompatibilitas biner dengan semua versi Android/Termux belum diverifikasi. Hapus biner yang gagal dan pasang agent yang kompatibel dari sumber resmi ngrok.
- **HTTP 401/403 dari GitHub:** token salah/kedaluwarsa atau tidak memiliki akses Contents write ke repo.
- **HP kedua melihat offline:** buka NEXORA di HP pertama dan pilih mode server; periksa log `tail -f ~/.config/nexora/boot.log` serta `~/.config/nexora/ngrok.log`.
- **ngrok error domain:** buka dashboard ngrok dan klaim development domain gratis yang ditetapkan untuk akun, bila diminta.

## Keamanan dan batasan
- Jangan membagikan pasangan code, ngrok authtoken, GitHub token, atau file `~/.config/nexora/ngrok.env`.
- Kode pasangan membatasi perangkat pasangan, tetapi prototipe ini belum end-to-end encrypted. Gunakan hanya untuk uji coba dengan orang tepercaya.
- Ketika HP pertama mati atau server aplikasinya tertutup, chat tidak dapat dikirim. Helper bukan hosting 24/7.
- Batas attachment 150 MiB hanya batas kode aplikasi per file. Ngrok free memiliki kuota trafik bulanan (misalnya 1 GB/bulan pada dokumentasi saat ini), sehingga transfer video besar bisa menghabiskan kuota. Keberhasilan unggahan bergantung pada koneksi dan ruang penyimpanan.
