# Anti-NSFW Android Uygulaması (Geliştirme Durduruldu)

[English Version](README.md)

## Genel Bakış

Bu Android uygulaması, ekranda görüntülenen potansiyel olarak Çalışmaya Uygun Olmayan (NSFW) içeriği **cihaz üzerinde, gerçek zamanlı olarak algılar**. Sunucuya ihtiyaç duymadan hassas içeriği tanımlamak ve isteğe bağlı olarak gizlemek için ekran görüntüsü yakalama, verimli değişiklik algılama, yerel yapay zeka modeli çıkarımı (Çıplaklık ve Cinsiyet tespiti) ve ekran katmanları kullanır.

**Hedef Kitle:** Bu uygulamanın hedeflenen kullanıcıları arasında dini olarak yasak (haram) içeriklerden kaçınmak isteyen Müslümanlar ve çevrimiçi içeriğin artan cinselleştirilmesinden genel olarak rahatsız olan ve görüntülerini filtrelemek isteyen bireyler bulunmaktadır. Ancak, aşağıda belirtilen teknik zorluklar nedeniyle uygulama şu anda bu hedefi etkili bir şekilde yerine getirememektedir.

**Not:** Bu projenin geliştirilmesi, aşağıdaki "Mevcut Durum ve Zorluklar" bölümünde ayrıntılı olarak açıklanan katman mekanizmasıyla ilgili önemli bir zorluk nedeniyle şu anda durdurulmuştur. Bu depo, başkalarının yaklaşımı ilginç bulabileceği veya mevcut engellerin üstesinden gelebileceği umuduyla paylaşılmaktadır.

## Özellikler

*   **Cihaz Üzerinde Gerçek Zamanlı Analiz:** Ekran içeriğini doğrudan kullanıcının cihazında yakalar ve analiz eder (yaklaşık her 200-300ms'de bir).
*   **NSFW Tespiti:** Potansiyel olarak müstehcen içeriği ve yüzleri/cinsiyetleri tanımlamak için yerel TensorFlow Lite modellerini (`NudeDetector.kt`, `GenderDetector.kt`) kullanır.
*   **Verimli Ekran İzleme:** Pil ve kaynak tasarrufu sağlamak için yalnızca önemli görsel değişiklikler meydana geldiğinde kareleri işlemek üzere `ScreenCaptureManager` (Media Projection kullanarak) ve `ScreenChangeDetector` (bitmap hashing) kullanır.
*   **Katman Sistemi:** Tespit edilen alanları gizlemek için üzerlerine özelleştirilebilir katmanlar (`OverlayService.kt`, `OverlayHelper.kt`) görüntüler.
*   **Kaydırma Farkındalığı:** Performansı ve kullanıcı deneyimini iyileştirmek için aktif kaydırma sırasında analizi duraklatmak üzere `ScrollDetectionService` (Erişilebilirlik Hizmeti) ile entegre olur.
*   **Servisler Arası İletişim:** Ana `ScreenshotService` ile `OverlayService` arasında güvenilir ve verimli iletişim için Android'in `Messenger` API'sini kullanır.
*   **Performans İzleme:** `MainActivity` içerisinde temel performans metriklerini (ör. çıkarım süresi) izler ve görüntüler.
*   **Kullanıcı Kontrolü:** Kullanıcıların `MainActivity` ve kalıcı bir bildirim aracılığıyla izleme hizmetini başlatmasına/durdurmasına olanak tanır.

## Ana Bileşenler

*   **`MainActivity.kt`**: İzinleri (Ekran Yakalama, Katman, Bildirimler) yönetmek, hizmeti başlatmak/durdurmak ve performans istatistiklerini görüntülemek için kullanıcı arayüzü.
*   **`ScreenshotService.kt`**: Tüm süreci yöneten ana arka plan hizmeti. Ekran yakalama döngüsünü yönetir, algılamayı koordine eder, `OverlayService` ile iletişim kurar ve hizmet yaşam döngüsünü ve ön plan bildirimini yönetir.
*   **`ScreenCaptureManager.kt`**: MediaProjection kurulumunu yönetir ve ekran içeriğini Bitmap olarak yakalar.
*   **`ScreenChangeDetector.kt`**: Tam bir analizi gerektirecek önemli bir değişiklik olup olmadığını belirlemek için ardışık ekran görüntülerini hashing kullanarak karşılaştırır.
*   **`NudeDetector.kt`**: Potansiyel çıplaklığı tespit etmek için yerel bir TensorFlow Lite modeli kullanarak çıkarım yapar ve sınırlayıcı kutular döndürür.
*   **`GenderDetector.kt`**: Yerel bir TensorFlow Lite modeli kullanarak (yüz algılama ve cinsiyet sınıflandırması için) çıkarım yapar ve sınırlayıcı kutular döndürür.
*   **`OverlayService.kt`**: `ScreenshotService`'ten `Messenger` aracılığıyla alınan sınırlayıcı kutu verilerine dayanarak ekran katmanlarını çizmekten ve yönetmekten sorumlu bağımsız bir hizmet.
*   **`OverlayHelper.kt`**: Katman görünümlerini yönetmede `OverlayService`'e yardımcı olur.
*   **`ScrollDetectionService.kt`**: Sistem genelinde kaydırma olaylarını algılayan ve analizi geçici olarak duraklatması için `ScreenshotService`'e bildirimde bulunan bir Erişilebilirlik Hizmeti.
*   **`BitmapPool.kt`**: Bellek ayırmayı/çöp toplamayı azaltmak için mtemelen Bitmap nesnelerini yönetir.
*   **`PerformanceStats.kt`**: SharedPreferences kullanarak performans metriklerini kaydetme ve yükleme işlemlerini yönetir.
*   **`StopScreenshotServiceReceiver.kt`**: Hizmet bildiriminden gelen durdurma eylemini işlemek için bir `BroadcastReceiver`.
*   **(Bitmap havuzu, performans istatistikleri, servis kontrolü için diğer bileşenler...)**

## Nasıl Çalışır (Amaçlanan Akış)

1.  Kullanıcı `MainActivity` aracılığıyla gerekli izinleri (Ekran Yakalama, Katman) verir.
2.  Kullanıcı `MainActivity` üzerinden hizmeti başlatır.
3.  `ScreenshotService` başlar, dedektörleri başlatır, `OverlayService`'e bağlanır ve `ScreenCaptureManager`'ı başlatır.
4.  Hizmet bir döngüye girer (her ~200-300ms'de bir):
    a.  `ScreenCaptureManager` aracılığıyla mevcut ekranı yakalar.
    b.  `ScrollDetectionService` aracılığıyla kaydırma olup olmadığını kontrol eder. Evet ise, kısa bir süre duraklar.
    c.  Yeni kareyi `ScreenChangeDetector` kullanarak bir öncekiyle karşılaştırır. Önemli bir değişiklik yoksa bir sonraki iterasyona atlar.
    d.  Değişiklik varsa, yakalanan Bitmap üzerinde `NudeDetector` ve `GenderDetector` kullanarak çıkarım yapar.
    e.  Tespit edilen sınırlayıcı kutuların birleştirilmiş listesini `Messenger` (`MSG_UPDATE_OVERLAYS`) aracılığıyla `OverlayService`'e gönderir.
    f.  Performans istatistiklerini günceller.
5.  `OverlayService` sınırlayıcı kutuları alır ve buna göre ekranda katmanları çizer/günceller.

## Mevcut Durum & Zorluklar (Proje Durduruldu)

**Arka Plan & Motivasyon:** Mevcut MediaProjection (ekran yakalama) yaklaşımı, diğer yöntemler araştırıldıktan sonra seçilmiştir. Önceki bir deneme, görüntüleri ekrana *render edilmeden önce* yakalamak için Xposed Framework'ü kullanmayı içeriyordu. Umut verici olsa da, bu yaklaşım WebView gibi karmaşık görünümlerdeki içeriği güvenilir bir şekilde yakalamada önemli engellerle karşılaştı ve kritik olarak, root erişimi gerektiriyordu, bu da onu genel bir kitle için uygunsuz hale getiriyordu. Root gerektirmeyen MediaProjection yöntemi ise maalesef aşağıda açıklanan geri besleme döngüsüne yol açtı.

**Overlay Geri Besleme Döngüsü:** Geliştirmeyi durdurmaya yol açan temel zorluk bir **overlay geri besleme döngüsüdür**:

1.  **Algılama:** Uygulama, bir ekran bölgesindeki NSFW içeriği başarıyla algılar.
2.  **Gizleme:** `OverlayService` tarafından algılanan bölgenin üzerine bir katman çizilir.
3.  **Yeniden Analiz:** `ScreenCaptureManager` tarafından yakalanan *bir sonraki* ekran görüntüsü, kaçınılmaz olarak az önce çizilen katmanı içerir.
4.  **Yanlış Negatif:** Bu yeni ekran görüntüsü (katmanı içeren) `NudeDetector` tarafından analiz edildiğinde, orijinal NSFW içerik artık katman tarafından gizlenmiştir. Bu nedenle dedektör artık bölgeyi pozitif olarak tanımlamaz.
5.  **Katman Kaldırma/Titreşim:** Sonuç olarak, `OverlayService` bir sonraki güncellemede katmanı kaldırabilir (bölge artık işaretlenmediği için), bu da orijinal içeriğin yeniden görünmesine neden olarak potansiyel bir algılama döngüsüne ve görsel titreşime yol açar.

**Sorun Kısıtlamaları:**

*   Bir ekran görüntüsü almadan *önce* katmanları geçici olarak gizlemek düşünüldü ancak kullanıcı deneyimine ve anında gizlemenin temel amacına zarar verdiği kabul edildi. İçerik altta görünürken katmanın kalıcı olması gerekir.
*   `ScreenCaptureManager`'ın, kendi uygulamasının `OverlayService`'i tarafından çizilen katmanları *içermeden* ekranı yakalamasını sağlamanın bir yolunu bulmak, standart Android API'leri ile zor veya imkansız hale geldi.

Bu geri besleme döngüsü, tespit edilen içeriğin istikrarlı ve güvenilir bir şekilde gizlenmesini engeller. **Bu zorluğun üstesinden gelmek, bu projeyi uygulanabilir kılmak için gereken ana görevdir.**

## Teknoloji Yığını

*   **Dil:** Kotlin
*   **Platform:** Android SDK
*   **Ana Bileşenler:** Android Servisleri (Ön Plan, Erişilebilirlik), Media Projection API, Messenger API (IPC), TensorFlow Lite (cihaz üzerinde çıkarım için), Canvas (katmanlar için).
*   **Eşzamanlılık:** Kotlin Coroutines
*   **Loglama:** Android Logcat (`Log.d`, `Log.e`, vb.)

## Kurulum & Kullanım (Deneysel Amaçlı)

1.  Depoyu klonlayın.
2.  Projeyi Android Studio'da açın.
3.  Uygulamayı bir Android cihazda veya emülatörde derleyin ve çalıştırın.
4.  `MainActivity` tarafından istendiğinde gerekli izinleri verin:
    *   Ekran Yakalama
    *   Diğer Uygulamaların Üzerinde Gösterme
    *   Bildirimler (Android 13+)
    *   Cihazın Ayarlar bölümünden Erişilebilirlik Hizmetini (`ScrollDetectionService`) etkinleştirin.
5.  Uygulamadaki "Hizmeti Başlat" düğmesine dokunun. *Tespit edilen içerikte potansiyel katman titremesi sorununu gözlemleyin.*

## Katkıda Bulunma

Orijinal yazar tarafından aktif geliştirme durdurulmuş olsa da, overlay geri besleme döngüsü sorununu nasıl çözeceğinize dair katkılar veya fikirler memnuniyetle karşılanır. Potansiyel çözümleri tartışmak için lütfen bir issue açın.
