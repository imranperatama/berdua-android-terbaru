# Catatan keamanan NEXORA

- Jaga kode pasangan tetap privat; kode itu memberi akses kepada perangkat yang dipasangkan.
- Jangan menyimpan token GitHub/ngrok di source code, APK, ZIP, `tunnel.json`, atau commit. Helper Termux menyimpan token secara lokal di `~/.config/nexora/ngrok.env` dengan izin terbatas.
- `tunnel.json` adalah file publik dan hanya boleh memuat alamat HTTPS tunnel, status online, serta waktu pembaruan. Jangan masukkan token atau kode pasangan.
- Chat ini belum memakai enkripsi end-to-end. HTTPS melindungi koneksi ke tunnel, tetapi tidak membuat sistem ini menjadi messenger E2E.
- Konten dan lampiran disimpan di HP host; uninstall aplikasi atau menghapus data aplikasi dapat menghapus data lokal.
- Berbagi lokasi mengirim data lokasi ke perangkat yang dipasangkan. Gunakan hanya dengan persetujuan kedua pengguna.
