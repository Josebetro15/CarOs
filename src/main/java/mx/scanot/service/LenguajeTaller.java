package mx.scanot.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Corrige ortografía en textos del taller.
 * No toca nombres ni apellidos. En el inventario solo deja vocabulario de mecánica.
 * Las groserías se rechazan en cualquier campo.
 */
@Service
public class LenguajeTaller {
  private static final Pattern PALABRA = Pattern.compile("[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+");
  private static final Set<String> FRENO = Set.of(
      "de", "del", "la", "las", "el", "los", "un", "una", "y", "o", "u", "e", "a", "en", "con", "sin",
      "para", "por", "al", "su", "sus", "que", "se", "es", "son", "esta", "está", "este", "estos");
  private static final Set<String> GROSERIAS = Set.of(
      "puto", "puta", "putos", "putas", "pendejo", "pendeja", "pendejos", "pendeja", "cabron", "cabrona",
      "cabrones", "verga", "chingar", "chingada", "chingado", "chingados", "chinga", "pinche", "pinches",
      "mierda", "joder", "jodido", "jodida", "culero", "culera", "panocha", "maricon", "marica", "joto",
      "jota", "idiota", "estupido", "estupida", "imbecil", "maldito", "maldita", "hijo de puta",
      "hijueputa", "guey", "wey", "naco", "puñetas", "coño", "carajo", "mamon", "mamona", "ojete",
      "pito", "verga", "zorra", "perra");
  private static final Set<String> RAICES = Set.of(
      "pendej", "cabron", "chinga", "mierd", "culer", "maric", "pinche", "hijueput", "estup");

  private final Map<String, List<String>> general = new HashMap<>();
  private final Map<String, List<String>> mecanica = new HashMap<>();
  private final Set<String> marcas = new HashSet<>();

  public LenguajeTaller() {
    cargar(general, GENERAL);
    cargar(mecanica, MECANICA);
    for (String m : MARCAS.split("\\s+")) marcas.add(plano(m));
  }

  public String persona(String texto) {
    if (texto == null) return "";
    String limpio = texto.trim().replaceAll("\\s+", " ");
    if (limpio.isEmpty()) return "";
    prohibidas(limpio);
    return limpio;
  }

  /** Corrige faltas si la palabra se parece a una conocida. Deja los nombres que no estén en el diccionario. */
  public String corregir(String texto) {
    if (texto == null) return "";
    String limpio = texto.trim().replaceAll("\\s+", " ");
    if (limpio.isEmpty()) return "";
    prohibidas(limpio);
    return reescribir(limpio, false);
  }

  /** Inventario: solo piezas, sistemas y marcas de auto. Corrige la ortografía de esas palabras. */
  public String pieza(String texto) {
    if (texto == null) return "";
    String limpio = texto.trim().replaceAll("\\s+", " ");
    if (limpio.isEmpty()) return "";
    prohibidas(limpio);
    return reescribir(limpio, true);
  }

  private String reescribir(String texto, boolean soloMecanica) {
    Matcher m = PALABRA.matcher(texto);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String token = m.group();
      if (!Character.isLetter(token.charAt(0))) {
        out.append(token);
        continue;
      }
      if (token.chars().anyMatch(Character::isDigit)) {
        out.append(token);
        continue;
      }
      String plano = plano(token);
      if (plano.length() < 2) {
        out.append(token);
        continue;
      }
      if (soloMecanica) {
        if (FRENO.contains(plano) || marcas.contains(plano)) {
          out.append(token);
          continue;
        }
        String fija = cercana(plano, token, mecanica, 1);
        if (fija == null) {
          throw new IllegalArgumentException(
              "En el inventario solo se aceptan palabras de mecánica. «" + token + "» no corresponde.");
        }
        out.append(aplicarMayuscula(token, fija));
        continue;
      }
      String fija = cercana(plano, token, general, plano.length() >= 8 ? 2 : 1);
      out.append(fija == null ? token : aplicarMayuscula(token, fija));
    }
    return out.toString().replaceAll("\\s+", " ").trim();
  }

  private String cercana(String plano, String original, Map<String, List<String>> dic, int maxDist) {
    List<String> exacta = dic.get(plano);
    if (exacta != null) {
      if (exacta.size() == 1) return exacta.get(0);
      return null;
    }
    if (plano.length() < 4) return null;
    String mejor = null;
    int mejorDist = maxDist + 1;
    int empates = 0;
    for (Map.Entry<String, List<String>> e : dic.entrySet()) {
      String clave = e.getKey();
      if (clave.charAt(0) != plano.charAt(0)) continue;
      if (Math.abs(clave.length() - plano.length()) > maxDist) continue;
      int d = distancia(plano, clave);
      if (d == 0 || d > maxDist) continue;
      if (!pareceFalta(plano, clave)) continue;
      if (e.getValue().size() != 1) continue;
      if (d < mejorDist) {
        mejorDist = d;
        mejor = e.getValue().get(0);
        empates = 1;
      } else if (d == mejorDist) {
        empates++;
      }
    }
    return empates == 1 ? mejor : null;
  }

  private static boolean pareceFalta(String escrito, String conocido) {
    int n = Math.min(3, Math.min(escrito.length(), conocido.length()));
    return escrito.regionMatches(0, conocido, 0, n);
  }

  private void prohibidas(String texto) {
    String plano = plano(texto);
    if (plano.contains("hijo de puta") || plano.contains("hijueputa")) {
      throw new IllegalArgumentException("Esa palabra no está permitida.");
    }
    for (String palabra : plano.split(" ")) {
      if (palabra.isBlank()) continue;
      String seca = palabra.replaceAll("(.)\\1{2,}", "$1$1");
      if (GROSERIAS.contains(seca)) throw new IllegalArgumentException("Esa palabra no está permitida.");
      for (String raiz : RAICES) {
        if (seca.startsWith(raiz)) throw new IllegalArgumentException("Esa palabra no está permitida.");
      }
    }
  }

  private static void cargar(Map<String, List<String>> destino, String lista) {
    for (String palabra : lista.trim().split("\\s+")) {
      if (palabra.isBlank()) continue;
      List<String> ya = destino.computeIfAbsent(plano(palabra), k -> new ArrayList<>());
      if (!ya.contains(palabra)) ya.add(palabra);
    }
  }

  private static String aplicarMayuscula(String original, String fija) {
    if (original.chars().allMatch(Character::isUpperCase)) return fija.toUpperCase(Locale.forLanguageTag("es"));
    if (Character.isUpperCase(original.charAt(0))) {
      return Character.toUpperCase(fija.charAt(0)) + fija.substring(1);
    }
    return fija;
  }

  static String plano(String texto) {
    String n = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return n.toLowerCase(Locale.forLanguageTag("es"));
  }

  private static int distancia(String a, String b) {
    int[] prev = new int[b.length() + 1];
    int[] cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) prev[j] = j;
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + costo);
      }
      int[] tmp = prev;
      prev = cur;
      cur = tmp;
    }
    return prev[b.length()];
  }

  private static final String MARCAS =
      "nissan chevrolet chevy vw volkswagen toyota honda mazda ford hyundai kia seat renault "
          + "dodge ram jeep bmw audi mercedes peugeot suzuki mitsubishi fiat chrysler gmc cadillac "
          + "versa hilux jetta beat sentra march aveo spark tsuru gol pointer vento polo golf "
          + "corolla civic crv rav4 np300 silverado cheyenne ranger lobo frontier";

  private static final String MECANICA =
      "aceite filtro bujía bobina banda balata balatas disco discos tambor amortiguador "
          + "amortiguadores rótula rótulas terminal terminales horquilla suspensión muelle "
          + "resorte espiral cremallera dirección bomba agua radiador manguera termostato "
          + "anticongelante refrigerante fan clutch ventilador alternador marcha batería "
          + "fusible relevador arnés cable cables bujías cables de bujía sensor sensores "
          + "oxígeno maf map tps cigüeñal árbol levas knock knock-sensor catalizador "
          + "convertidor múltiple escape silenciador tubo gasolina inyector inyectores "
          + "bomba gasolina riel regulador cuerpo aceleración mariposa admisión "
          + "carburador biela pistón anillos camisa culata junta empaque head gasket "
          + "válvula válvulas retenes cadena distribución tensor guía polea poleas "
          + "tensor banda serpentina clutch collarín plato disco clutch volante "
          + "transmisión caja velocidades clutch kit diferencial flecha homocinética "
          + "junta homocinética tripoide cubrepolvo flecha cardán cruceta "
          + "freno frenos líquido balata caliper cáliper pistón de caliper "
          + "manguera de freno cilindro maestro bomba de freno abs sensor abs "
          + "llanta llantas rin rinés válvula de llanta alineación balanceo "
          + "amortiguador delantero trasero birlos birlo rotula "
          + "faro faro calavera cuartos direccional espejo parabrisas "
          + "limpiaparabrisas pluma hules cofre salpicadera fascia defensa "
          + "puerta cajuela cofre bisagra chapa cerradura elevador vidrio "
          + "asiento tapicería alfombra vestidura volante palanca "
          + "aceite de motor aceite de transmisión aceite de diferencial "
          + "filtro de aceite filtro de aire filtro de gasolina filtro de cabina "
          + "bujía de encendido bobina de encendido cable de bujía "
          + "sensor de oxígeno sensor maf sensor map sensor tps sensor abs "
          + "bomba de agua bomba de gasolina bomba de dirección "
          + "banda de distribución banda de accesorios tensor de banda "
          + "balatas delanteras balatas traseras discos de freno "
          + "refrigerante anticongelante líquido de frenos aceite hidráulico "
          + "refacción refacciones pieza piezas repuesto repuestos "
          + "motor frenos suspensión dirección encendido eléctrico eléctrico "
          + "enfriamiento combustible escape clutch transmisión frenos "
          + "afinación servicio mantenimiento diagnóstico reparación "
          + "cambio ajuste limpieza calibración reemplazo instalación "
          + "delantero delantera trasero trasera izquierdo izquierda derecho derecha "
          + "superior inferior interno externa banco cilindro "
          + "kilometraje kilometros kilómetros afinación mayor menor "
          + "catalizador convertidor catalítico múltiple de escape "
          + "termómetro termostato tapón de radiador depósito "
          + "batería alternador marcha solenoide carbones "
          + "fusilera caja de fusibles relevador relay "
          + "orquilla horquilla brazo tijera buje bujes "
          + "cremallera bomba hidráulica depósito de dirección "
          + "empaque de culata junta de cabeza tornillos de cabeza "
          + "pistones anillos metales de biela metales de bancada "
          + "árbol de levas cadena de distribución guías "
          + "volante de motor plato de clutch collarín "
          + "flecha homocinética junta homocinética "
          + "caliper cáliper cilindro de rueda "
          + "amortiguador espiral tope de suspensión base de amortiguador "
          + "rótula inferior rótula superior terminal de dirección "
          + "aceite sintético aceite mineral grasa "
          + "filtro habitáculo filtro de polen "
          + "bujía iridium bujía platino "
          + "sensor de temperatura sensor de cigüeñal sensor de árbol "
          + "válvula pcv válvula egr válvula iac "
          + "cuerpo de aceleración mariposa "
          + "inyector bomba de alta presión riel común "
          + "catalizador sonda lambda "
          + "mofle tubo de escape abrazadera "
          + "radiador condensador evaporador compresor "
          + "gas refrigerante clutch de compresor "
          + "banda unique serpentina "
          + "llanta refacción gato cruceta de llanta "
          + "birlo tuerca de rueda copa de rin "
          + "aceite atf líquido de dirección "
          + "reten retenes sello semihusillo "
          + "cojinete rodamiento balero masa "
          + "cubo de rueda rodamiento de rueda "
          + "pastilla pastillas zapata zapatas "
          + "disco ventilado tambor de freno "
          + "líquido dot bomba abs módulo abs "
          + "arnés conector terminal eléctrica "
          + "foco led halógeno xenón "
          + "plumilla hule de pluma depósito de agua "
          + "cerradura chapas switch de encendido "
          + "switch de freno bulbo de reversa "
          + "palanca de velocidades varillaje "
          + "soporte de motor soporte de caja "
          + "base de amortiguador tornillería tornillo tuerca rondana "
          + "abrazadera manguera inferior superior "
          + "tapón carter empaque de cárter "
          + "cárter bomba de aceite colador "
          + "varilla de aceite tapón de aceite "
          + "correa distribución kit de distribución "
          + "polea damper polea de cigüeñal "
          + "tensor hidráulico guía de cadena "
          + "empaque múltiple admisión escape "
          + "válvula de escape válvula de admisión "
          + "resorte de válvula platillos "
          + "árbol intermedio "
          + "módulo de encendido bobina individual "
          + "cables de bujía tapa de distribuidor rotor "
          + "distribuidor platino condensador "
          + "carburador flotador chicler "
          + "filtro de canasta prefiltro "
          + "sensor de nivel sensor de presión "
          + "switch de presión de aceite "
          + "bulbo de temperatura "
          + "electroventilador resistencia "
          + "depósito recuperador tapón "
          + "manguera de calefacción núcleo calefactor "
          + "evaporador válvula de expansión "
          + "filtro deshidratador "
          + "compresor de aire acondicionado "
          + "condensador "
          + "clutch kit disco plato collarín "
          + "volante bimasa "
          + "retén de cigüeñal reten de leva "
          + "junta homocinetica lado rueda lado caja "
          + "flecha intermedia "
          + "cruceta de cardán "
          + "diferencial piñón corona "
          + "aceite 75w90 80w90 5w30 5w40 10w40 20w50 "
          + "filtro sellado filtro ecológico "
          + "bujía ngk bujía bosch "
          + "balata cerámica balata semimetálica "
          + "disco rayado disco nuevo "
          + "amortiguador de gas amortiguador hidráulico "
          + "base cazoleta "
          + "goma de barra barra estabilizadora enlace "
          + "terminal axial "
          + "fuelle de dirección "
          + "bomba eléctrica de dirección "
          + "cremallera hidráulica cremallera eléctrica "
          + "manguera de presión manguera de retorno "
          + "líquido de frenos dot3 dot4 "
          + "grasa de litio grasa de chasis "
          + "limpiador de inyectores aditivo "
          + "refrigerante rosa verde "
          + "agua desmineralizada "
          + "empaque de tapa de válvulas "
          + "tapa de punterías "
          + "tornillo de culata "
          + "meter metales "
          + "anillo de fuego anillo de aceite "
          + "pistón estándar pistón sobre medida "
          + "camisa seca camisa húmeda "
          + "culata cepillada "
          + "prueba de compresión "
          + "escáner diagnóstico dtc código falla "
          + "arnés de sensor conector de sensor "
          + "fusible de 10 fusible de 15 mini fusible "
          + "relevador de bomba relevador de marcha "
          + "solenoide de marcha bendix "
          + "carbones de alternador regulador "
          + "polea de alternador clutch de alternador "
          + "batería de gel batería sellada "
          + "terminal de batería cable de corriente "
          + "tierra de motor "
          + "switch de ignición cilindro de encendido "
          + "llave de encendido "
          + "bobina tipo lápiz bobina de plato "
          + "sensor de detonación "
          + "sensor de flujo de aire "
          + "cuerpo de aceleración electrónico "
          + "válvula iac motor de ralentí "
          + "válvula egr enfriador egr "
          + "válvula pcv manguera pcv "
          + "cánister válvula de purga "
          + "sonda lambda antes después del catalizador "
          + "convertidor catalítico "
          + "múltiple de escape empaque de múltiple "
          + "tornillo de múltiple "
          + "tubo de escape flexible "
          + "abrazadera de escape "
          + "silenciador resonador "
          + "punta de escape "
          + "inyector sucio inyector nuevo "
          + "riel de inyectores "
          + "regulador de presión "
          + "bomba sumergible prefiltro de bomba "
          + "flotador de gasolina "
          + "manguera de retorno de gasolina "
          + "filtro en línea "
          + "tanque de gasolina "
          + "tapa de gasolina "
          + "cuello de llenado "
          + "canister de vapores "
          + "válvula check "
          + "aceite de caja automática "
          + "filtro de transmisión "
          + "empaque de cárter de transmisión "
          + "solenoides de transmisión "
          + "cuerpo de válvulas "
          + "convertidor de par "
          + "aceite de diferenciales "
          + "reten de piñón "
          + "balero de rueda delantero "
          + "masa con balero "
          + "birlos de rueda "
          + "tuerca de eje "
          + "copas de rin "
          + "válvula de rin "
          + "sensor de presión de llanta tpms "
          + "alineación balanceo rotación "
          + "amortiguador delantero izquierdo "
          + "base superior "
          + "tope de rebote "
          + "guardapolvo "
          + "rótula de suspensión "
          + "brazo de control "
          + "buje de horquilla "
          + "barra estabilizadora "
          + "enlace de barra "
          + "goma de barra "
          + "terminal exterior terminal interior "
          + "cremallera completa "
          + "bomba de dirección hidráulica "
          + "depósito de aceite hidráulico "
          + "manguera de alta "
          + "aceite atf dirección "
          + "faro delantero calavera trasera "
          + "cuarto direccional "
          + "foco de freno "
          + "arnés de faro "
          + "espejo lateral "
          + "cristal de puerta "
          + "elevador manual eléctrico "
          + "manija de puerta "
          + "chapa de cajuela "
          + "amortiguador de cajuela "
          + "bisagra de cofre "
          + "chapa de cofre "
          + "rejilla "
          + "fascia delantera "
          + "alma de defensa "
          + "soporte de fascia "
          + "salpicadera "
          + "costado "
          + "marco de radiador "
          + "traviesa "
          + "cuna de motor "
          + "soporte derecho soporte izquierdo "
          + "soporte trasero de motor "
          + "base de caja "
          + "varilla selectora "
          + "fundas de palanca "
          + "pomos "
          + "volante airbag "
          + "cinta de airbag "
          + "bolsa de aire "
          + "sensor de impacto "
          + "módulo airbag "
          + "cinturón pretensor "
          + "hebilla "
          + "asiento delantero "
          + "vestidura de asiento "
          + "espuma "
          + "alfombra de piso "
          + "tapete "
          + "guarnecidos "
          + "cielo "
          + "visor "
          + "consola "
          + "radio arnés de radio "
          + "bocina "
          + "tweeter "
          + "antena "
          + "motor de limpiaparabrisas "
          + "varillaje de plumas "
          + "depósito lavaparabrisas "
          + "bomba de agua de parabrisas "
          + "manguera de lavado "
          + "hule de plumilla "
          + "plumilla de 20 plumilla de 18 "
          + "servicio de frenos "
          + "cambio de aceite "
          + "afinación menor afinación mayor "
          + "cambio de banda "
          + "cambio de bomba "
          + "diagnóstico de escáner "
          + "reparación de fuga "
          + "ajuste de frenos "
          + "purga de frenos "
          + "rectificado de discos "
          + "cambio de balatas "
          + "alineación de dirección "
          + "balanceo de llantas "
          + "rotación de llantas "
          + "cambio de amortiguadores "
          + "cambio de rótulas "
          + "cambio de terminales "
          + "servicio de suspensión "
          + "carga de aire acondicionado "
          + "detección de fugas "
          + "lavado de inyectores "
          + "limpieza de cuerpo de aceleración "
          + "cambio de bujías "
          + "cambio de filtros "
          + "revisión de niveles "
          + "anticongelante concentrado "
          + "agua para batería no "
          + "terminal positiva terminal negativa "
          + "portafusibles "
          + "zócalo de relevador "
          + "arnés principal "
          + "masa de carrocería "
          + "cable de batería a marcha "
          + "cable de tierra "
          + "solenoide "
          + "bendix de marcha "
          + "horquilla de marcha "
          + "bujes de marcha "
          + "piñón de marcha "
          + "corona de volante "
          + "regulador de voltaje "
          + "diodos de alternador "
          + "estator rotor "
          + "carbones "
          + "polea loca polea tensora "
          + "banda micro v "
          + "damper "
          + "tornillo de damper "
          + "cuña de cigüeñal "
          + "sello delantero "
          + "sello trasero "
          + "empaque de bomba de agua "
          + "tornillos de bomba "
          + "aspas de ventilador "
          + "clutch de ventilador "
          + "radiador de aluminio "
          + "tapon de radiador "
          + "manguera superior del radiador "
          + "manguera inferior del radiador "
          + "abrazaderas "
          + "termostato de 82 termostato de 88 "
          + "carcasa de termostato "
          + "empaque de termostato "
          + "sensor ect "
          + "bulbo "
          + "depósito de expansión "
          + "tapón de depósito "
          + "refrigerante orgánico "
          + "junta de múltiple de admisión "
          + "tornillo de múltiple de admisión "
          + "empaque de escape "
          + "espárrago de escape "
          + "tuerca de escape "
          + "catalizador de dos vías "
          + "brida de escape "
          + "empaque de brida "
          + "sensor de oxígeno delantero "
          + "sensor de oxígeno trasero "
          + "conector del sensor "
          + "extensión de arnés "
          + "válvula de purga del cánister "
          + "manguera de vapores "
          + "tapa del tanque "
          + "empaque de tapa "
          + "cuello de llenado de gasolina "
          + "manguera de llenado "
          + "filtro de bomba "
          + "aro de bomba "
          + "empaque de bomba "
          + "regulador de presión de gasolina "
          + "manguera de alta presión "
          + "riel common rail "
          + "inyector diésel "
          + "bujía de precalentamiento "
          + "módulo de bujías "
          + "relevador de precalentamiento "
          + "sensor de riel "
          + "válvula reguladora "
          + "bomba de alta "
          + "filtro de diésel "
          + "decantador de agua "
          + "sensor de agua en combustible "
          + "emisiones lubricación universal compatible genérico genérica calentador de combustible";

  private static final String GENERAL =
      MECANICA + " "
          + "el la los las un una de del al y o u e a en con sin para por que se es son "
          + "esta está este estos estas ese esa esos esas "
          + "ya no si sí muy más menos bien mal listo lista "
          + "hoy mañana tarde noche cuando entra llegó llego llega "
          + "unidad carro auto coche camioneta camión vehículo vehículos "
          + "cliente taller mecánico encargado supervisor "
          + "trabajo orden servicio revisión cambio ajuste "
          + "mensaje avance estado entrega factura "
          + "porque porque también entonces después antes durante "
          + "nuevo nueva usados usada original genérico genérica "
          + "precio existencia almacén proveedor ciudad días día "
          + "placa modelo marca año kilometraje salud "
          + "tapete tapetes radio llanta de refacción golpes visibles "
          + "herramienta gato triángulo extintor cable pasa corriente "
          + "documento tarjeta circulación verificación "
          + "hola gracias favor pendiente proceso cerrado abierta "
          + "abierta cerrada espera esperando pieza refacción "
          + "encargo encargaron encargada encargó "
          + "reparación reparado reparada "
          + "diagnóstico diagnosticado "
          + "calidad control facturación "
          + "ingreso salida "
          + "foto video evidencia "
          + "lado derecho izquierdo frente atrás "
          + "ruido fuga humo vibración falla fallas "
          + "código códigos lectura escáner reporte "
          + "cotización cotizar mejor precio "
          + "programar cita disponibilidad "
          + "listo para entrega se puede recoger "
          + "el cliente ya puede pasar "
          + "se pidió se cambió se revisó se ajustó se limpió "
          + "falta faltan sobra "
          + "torquímetro apriete libras "
          + "prueba de ruta "
          + "sin fugas sin ruidos "
          + "niveles correctos "
          + "batería con carga "
          + "compresión pareja "
          + "mezcla pobre mezcla rica "
          + "fallo de encendido "
          + "temperatura normal "
          + "presión de aceite "
          + "testigo encendido "
          + "check engine "
          + "listo el viernes "
          + "entra el jueves "
          + "la pieza llega mañana "
          + "unidad en el elevador "
          + "bahía uno bahía dos";
}
