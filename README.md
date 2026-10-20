# NEXORA — proyek lengkap

Paket utama aplikasi Android **NEXORA**. Isinya sudah mencakup proyek Android, kode aplikasi, `tunnel.json`, serta helper Termux/ngrok untuk HP pertama. ZIP ini tidak menyertakan token rahasia dan tidak menyertakan workflow GitHub; dua workflow berada di ZIP **NEXORA-GitHub-Tools.zip**.

## Isi dan fitur yang disiapkan

- UI chat gelap untuk dua perangkat yang dipasangkan dengan kode.
- Chat teks, foto, video, audio/rekaman suara, dan berbagi lokasi.
- Foto dapat dibuka dari percakapan, audio mempunyai kontrol pemutaran, video dibuka di pemutar, dan lampiran dapat disimpan lewat pemilih dokumen Android.
- Transfer lampiran berbasis file dengan batas kode aplikasi 150 MiB per berkas; transfer 57 MB/90 MB tetap bergantung pada koneksi, ruang penyimpanan, batas layanan tunnel, dan pengujian di perangkat nyata.
- Pesan/lampiran disimpan di penyimpanan privat HP yang menjadi host. Kapasitas nyata dibatasi ruang kosong perangkat; bukan penyimpanan tanpa batas.
- HP pertama bertindak sebagai server saat aplikasi dibuka dalam mode server. Helper Termux memantau server, menjalankan ngrok, menemukan alamat HTTPS dari API lokal ngrok, lalu memperbarui `tunnel.json` di GitHub. HP kedua membaca alamat itu ketika menghubungkan kembali.
- GitHub hanya dipakai untuk menemukan alamat tunnel, bukan untuk menyimpan isi chat/lampiran.

## Alur GitHub

1. Unduh ZIP ini, namanya **`NEXORA-Full-Project.zip`**.
2. Ekstrak **`NEXORA-GitHub-Tools.zip`** di HP/komputer.
3. Di repositori GitHub, pasang file workflow dari ZIP Tools di jalur `.github/workflows/` seperti dijelaskan pada `PETUNJUK-GITHUB-ID.md`.
4. Unggah `NEXORA-Full-Project.zip` ke root repositori dan commit.
5. Buka **Actions → 01 - Ekstrak Paket NEXORA → Run workflow**. Workflow akan menaruh isi paket pada root repositori dan menghapus file ZIP dari repo setelah sukses.
6. Setelah source masuk, **02 - Build APK** akan berjalan otomatis melalui perubahan source. Kalau tidak, jalankan dari Actions secara manual. Unduh artefak bernama `nexora-debug-apk` dari hasil run yang sukses.

## Setup ngrok di HP pertama

1. Ekstrak ZIP ini di perangkat yang bisa mengakses Termux, lalu pindah ke folder `tools/termux-ngrok/`.
2. Jalankan `bash setup.sh` dari Termux.
3. Siapkan akun ngrok dan token GitHub fine-grained untuk repositori ini dengan izin **Contents: Read and write**. Token dimasukkan saat setup dan hanya disimpan di folder privat Termux—jangan commit/kirim token itu.
4. Di aplikasi NEXORA pada HP pertama, tekan **Jadikan HP pertama · Server**. Pastikan server aktif; biarkan helper berjalan.
5. Di HP kedua, pilih **Sambungkan HP kedua**, kosongkan URL jika ingin alamat otomatis, lalu masukkan kode pasangan dari HP pertama.

Ngrok biasanya menggunakan development domain milik akun yang tetap sama saat agent dimulai ulang, selama domain akun yang benar dipakai. Setup domain/akun tetap diperlukan satu kali. Kuota/batas layanan ngrok gratis bisa membatasi transfer video besar.

## Batasan penting

- Ini prototipe chat untuk pengujian, belum end-to-end encrypted. Gunakan hanya dengan orang tepercaya dan jangan kirim informasi sensitif.
- Jika HP pertama mati, kehilangan internet, aplikasi server berhenti, atau Android mematikan proses, HP kedua tidak dapat mengakses server sampai host hidup kembali.
- Helper belum bisa menjamin hidup di latar belakang pada semua merek/versi Android. Izinkan aktivitas latar belakang untuk NEXORA dan Termux saat pengujian; Termux:Boot bisa dipasang terpisah untuk memulai helper setelah restart.
- Batas 150 MiB adalah batas kode aplikasi, bukan jaminan bahwa unggahan berkas sebesar itu selalu berhasil. Build APK dan pengujian dua HP harus tetap dilakukan melalui GitHub Actions/perangkat nyata.
