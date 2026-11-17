package mx.scanot.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.persistence.EntityManager;
import mx.scanot.domain.Rol;
import mx.scanot.domain.UpdateMedia;
import mx.scanot.domain.Usuario;
import mx.scanot.domain.Vehicle;
import mx.scanot.domain.VehicleUpdate;
import mx.scanot.repo.SignalRepo;
import mx.scanot.repo.UpdateMediaRepo;
import mx.scanot.repo.VehicleRepo;
import mx.scanot.repo.VehicleUpdateRepo;
import mx.scanot.service.MediaService;
import mx.scanot.domain.Cliente;
import mx.scanot.repo.ClienteRepo;
import mx.scanot.service.LenguajeTaller;
import mx.scanot.service.MailboxService;
import mx.scanot.service.Personas;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/app/vehiculos")
public class VehicleController {
  public static final List<String[]> KINDS = List.of(
      new String[]{"ingreso", "Unidad ingresada"},
      new String[]{"diagnostico", "Diagnóstico en curso"},
      new String[]{"piezas_pedidas", "Piezas encargadas"},
      new String[]{"en_reparacion", "En reparación"},
      new String[]{"listo", "Listo para entrega"},
      new String[]{"entregado", "Unidad entregada"}
  );

  private final VehicleRepo vehicles;
  private final SignalRepo signals;
  private final VehicleUpdateRepo updates;
  private final MediaService media;
  private final Personas personas;
  private final ClienteRepo clientes;
  private final MailboxService mailbox;
  private final LenguajeTaller lenguaje;
  private final UpdateMediaRepo archivos;
  private final EntityManager em;

  public VehicleController(VehicleRepo vehicles, SignalRepo signals, VehicleUpdateRepo updates, MediaService media,
                           Personas personas, ClienteRepo clientes, MailboxService mailbox, LenguajeTaller lenguaje,
                           UpdateMediaRepo archivos, EntityManager em) {
    this.vehicles = vehicles;
    this.signals = signals;
    this.updates = updates;
    this.media = media;
    this.personas = personas;
    this.clientes = clientes;
    this.mailbox = mailbox;
    this.lenguaje = lenguaje;
    this.archivos = archivos;
    this.em = em;
  }

  @GetMapping
  public String list(@AuthenticationPrincipal Usuario user, Model model) {
    List<Vehicle> lista = user.getRol() == Rol.CLIENTE
        ? vehicles.findByOwnerId(user.getId())
        : vehicles.findAll();
    Map<Long, String> portadas = new HashMap<>();
    for (Vehicle v : lista) {
      List<String> fotos = fotosDe(v);
      if (!fotos.isEmpty()) portadas.put(v.getId(), fotos.get(0));
    }
    model.addAttribute("user", user);
    model.addAttribute("vehicles", lista);
    model.addAttribute("portadas", portadas);
    model.addAttribute("staff", user.getRol() != Rol.CLIENTE);
    model.addAttribute("supervisor", user.getRol() == Rol.ENCARGADO);
    if (user.getRol() != Rol.CLIENTE) model.addAttribute("clientes", clientes.findAll());
    return "app/vehiculos";
  }

  @GetMapping("/inspeccion")
  public String inspeccion(@RequestParam(required = false) Long id, @AuthenticationPrincipal Usuario user, Model model) {
    List<Vehicle> list = user.getRol() == Rol.CLIENTE ? vehicles.findByOwnerId(user.getId()) : vehicles.findAll();
    if (list.isEmpty()) {
      model.addAttribute("user", user);
      model.addAttribute("vacio", true);
      return "app/inspeccion";
    }
    Vehicle current = list.get(0);
    int index = 0;
    if (id != null) {
      for (int i = 0; i < list.size(); i++) {
        if (list.get(i).getId().equals(id)) {
          current = list.get(i);
          index = i;
        }
      }
    }
    Vehicle prev = list.get((index - 1 + list.size()) % list.size());
    Vehicle next = list.get((index + 1) % list.size());
    model.addAttribute("user", user);
    model.addAttribute("v", current);
    model.addAttribute("prev", prev.getId());
    model.addAttribute("next", next.getId());
    model.addAttribute("total", list.size());
    model.addAttribute("numero", index + 1);
    model.addAttribute("vacio", false);
    model.addAttribute("fotos", fotosDe(current));
    return "app/inspeccion";
  }

  @PostMapping
  public String alta(@AuthenticationPrincipal Usuario user,
                     @RequestParam String plate,
                     @RequestParam String vin,
                     @RequestParam String brand,
                     @RequestParam String modelName,
                     @RequestParam Integer year,
                     @RequestParam Integer km,
                     @RequestParam(required = false) String inventario,
                     @RequestParam(required = false) String ownerId,
                     RedirectAttributes ra) {
    if (user.getRol() == Rol.CLIENTE) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    try {
      String placa = plate == null ? "" : plate.trim().toUpperCase();
      String serie = vin == null ? "" : vin.trim().toUpperCase();
      if (!placa.matches("[A-Z0-9-]{5,12}")) {
        throw new IllegalArgumentException("La placa debe tener de 5 a 12 letras, números o guiones.");
      }
      if (!serie.matches("[A-HJ-NPR-Z0-9]{11,17}")) {
        throw new IllegalArgumentException("El VIN debe tener de 11 a 17 caracteres, sin I, O ni Q.");
      }
      if (year == null || year < 1980 || year > 2035) {
        throw new IllegalArgumentException("El año debe estar entre 1980 y 2035.");
      }
      if (km == null || km < 0) throw new IllegalArgumentException("El kilometraje no puede ser negativo.");
      if (brand == null || brand.trim().length() < 2) throw new IllegalArgumentException("Escribe la marca.");
      if (modelName == null || modelName.trim().length() < 1) throw new IllegalArgumentException("Escribe el modelo.");
      String marca = lenguaje.persona(brand);
      String modelo = lenguaje.persona(modelName);
      String notas = inventario == null || inventario.isBlank() ? "" : lenguaje.corregir(inventario);
      if (vehicles.findByPlate(placa).isPresent()) throw new IllegalArgumentException("Esa placa ya está registrada.");
      if (vehicles.findByVin(serie).isPresent()) throw new IllegalArgumentException("Ese VIN ya está registrado.");
      Vehicle v = new Vehicle();
      v.setPlate(placa);
      v.setVin(serie);
      v.setBrand(marca);
      v.setModel(modelo);
      v.setYear(year);
      v.setKm(km);
      v.setHealth(100);
      v.setInventarioInicial(notas);
      if (ownerId != null && !ownerId.isBlank()) {
        long idDueno;
        try {
          idDueno = Long.parseLong(ownerId);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Ese cliente no existe.");
        }
        Cliente dueno = clientes.findById(idDueno)
            .orElseThrow(() -> new IllegalArgumentException("Ese cliente no existe."));
        v.setOwner(dueno);
      }
      vehicles.save(v);
      ra.addFlashAttribute("ok", "Unidad " + v.getPlate() + " registrada en recepción.");
      return "redirect:/app/vehiculos/" + v.getId();
    } catch (IllegalArgumentException e) {
      ra.addFlashAttribute("error", e.getMessage());
      return "redirect:/app/vehiculos";
    }
  }

  @GetMapping("/{id}")
  public String detail(@PathVariable Long id, @AuthenticationPrincipal Usuario user, Model model) {
    Vehicle v = vehicles.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (user.getRol() == Rol.CLIENTE && (v.getOwnerUserId() == null || !v.getOwnerUserId().equals(user.getId()))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    model.addAttribute("user", user);
    model.addAttribute("v", v);
    model.addAttribute("signals", signals.findByVehicleOrderByIdDesc(v));
    model.addAttribute("updates", updates.findByVehicleOrderByIdDesc(v));
    model.addAttribute("kinds", KINDS);
    model.addAttribute("staff", user.getRol() != Rol.CLIENTE);
    model.addAttribute("supervisor", user.getRol() == Rol.ENCARGADO);
    model.addAttribute("fotos", fotosDe(v));
    if (user.getRol() == Rol.ENCARGADO) model.addAttribute("clientes", clientes.findAll());
    return "app/vehiculo";
  }

  @PostMapping("/{id}/avance")
  public String avance(@PathVariable Long id,
                       @AuthenticationPrincipal Usuario user,
                       @RequestParam String kind,
                       @RequestParam String message,
                       @RequestParam(value = "files", required = false) List<MultipartFile> files,
                       RedirectAttributes ra) throws Exception {
    if (user.getRol() == Rol.CLIENTE) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    String texto;
    try {
      texto = lenguaje.corregir(message);
    } catch (IllegalArgumentException e) {
      ra.addFlashAttribute("error", e.getMessage());
      return "redirect:/app/vehiculos/" + id;
    }
    if (texto.length() < 3) {
      ra.addFlashAttribute("error", "Escribe el avance, mínimo 3 letras.");
      return "redirect:/app/vehiculos/" + id;
    }
    if (texto.length() > 2000) {
      ra.addFlashAttribute("error", "El mensaje no puede pasar de 2000 letras.");
      return "redirect:/app/vehiculos/" + id;
    }
    boolean kindOk = KINDS.stream().anyMatch(k -> k[0].equals(kind));
    if (!kindOk) {
      ra.addFlashAttribute("error", "Elige un estado válido.");
      return "redirect:/app/vehiculos/" + id;
    }
    Vehicle v = vehicles.findById(id).orElseThrow();
    VehicleUpdate u = new VehicleUpdate();
    u.setVehicle(v);
    personas.publica(u, user);
    u.setKind(kind);
    u.setMessage(texto);
    if (files != null) {
      int n = 0;
      for (MultipartFile f : files) {
        if (f.isEmpty() || n >= 3) continue;
        try {
          String url = media.save(f);
          UpdateMedia m = new UpdateMedia();
          m.setUpdate(u);
          m.setUrl(url);
          m.setMediaType(MediaService.kindOf(f));
          u.getMedia().add(m);
          n++;
        } catch (IllegalArgumentException e) {
          ra.addFlashAttribute("error", e.getMessage());
          return "redirect:/app/vehiculos/" + id;
        }
      }
    }
    updates.save(u);
    mailbox.notifyAvance(v, kind, u.getMessage());
    String aviso = "Avance publicado. El cliente recibe el aviso.";
    if (message != null && !texto.equals(message.trim().replaceAll("\\s+", " "))) {
      aviso += " Se corrigió la ortografía.";
    }
    ra.addFlashAttribute("ok", aviso);
    return "redirect:/app/vehiculos/" + id;
  }

  @PostMapping("/{id}/editar")
  public String editar(@PathVariable Long id,
                       @AuthenticationPrincipal Usuario user,
                       @RequestParam String plate,
                       @RequestParam String vin,
                       @RequestParam String brand,
                       @RequestParam String modelName,
                       @RequestParam Integer year,
                       @RequestParam Integer km,
                       @RequestParam Integer health,
                       @RequestParam(required = false) String inventario,
                       @RequestParam(required = false) String ownerId,
                       @RequestParam(value = "fotos", required = false) List<MultipartFile> fotos,
                       RedirectAttributes ra) {
    soloSupervisor(user);
    try {
      Vehicle v = vehicles.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
      String placa = plate == null ? "" : plate.trim().toUpperCase();
      String serie = vin == null ? "" : vin.trim().toUpperCase();
      if (!placa.matches("[A-Z0-9-]{5,12}")) {
        throw new IllegalArgumentException("La placa debe tener de 5 a 12 letras, números o guiones.");
      }
      if (!serie.matches("[A-HJ-NPR-Z0-9]{11,17}")) {
        throw new IllegalArgumentException("El VIN debe tener de 11 a 17 caracteres, sin I, O ni Q.");
      }
      if (year == null || year < 1980 || year > 2035) throw new IllegalArgumentException("El año debe estar entre 1980 y 2035.");
      if (km == null || km < 0) throw new IllegalArgumentException("El kilometraje no puede ser negativo.");
      if (health == null || health < 0 || health > 100) throw new IllegalArgumentException("La salud va de 0 a 100.");
      if (brand == null || brand.trim().length() < 2) throw new IllegalArgumentException("Escribe la marca.");
      if (modelName == null || modelName.trim().length() < 1) throw new IllegalArgumentException("Escribe el modelo.");
      vehicles.findByPlate(placa).filter(otro -> !otro.getId().equals(id))
          .ifPresent(otro -> { throw new IllegalArgumentException("Esa placa ya está registrada."); });
      vehicles.findByVin(serie).filter(otro -> !otro.getId().equals(id))
          .ifPresent(otro -> { throw new IllegalArgumentException("Ese VIN ya está registrado."); });
      v.setPlate(placa);
      v.setVin(serie);
      v.setBrand(lenguaje.persona(brand));
      v.setModel(lenguaje.persona(modelName));
      v.setYear(year);
      v.setKm(km);
      v.setHealth(health);
      v.setInventarioInicial(inventario == null || inventario.isBlank() ? "" : lenguaje.corregir(inventario));
      if (ownerId == null || ownerId.isBlank()) {
        v.setOwner(null);
      } else {
        long idDueno;
        try {
          idDueno = Long.parseLong(ownerId);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Ese cliente no existe.");
        }
        v.setOwner(clientes.findById(idDueno).orElseThrow(() -> new IllegalArgumentException("Ese cliente no existe.")));
      }
      vehicles.save(v);
      int n = guardarFotos(v, user, fotos, "Fotos de la unidad actualizadas.");
      ra.addFlashAttribute("ok", n > 0
          ? "Unidad actualizada. La imagen del zoom ya muestra las fotos nuevas."
          : "Unidad actualizada.");
    } catch (IllegalArgumentException e) {
      ra.addFlashAttribute("error", e.getMessage());
    } catch (Exception e) {
      ra.addFlashAttribute("error", "No se pudieron guardar las fotos.");
    }
    return "redirect:/app/vehiculos/" + id;
  }

  @PostMapping("/{id}/eliminar")
  @Transactional
  public String eliminar(@PathVariable Long id, @AuthenticationPrincipal Usuario user, RedirectAttributes ra) {
    soloSupervisor(user);
    Vehicle v = vehicles.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    for (VehicleUpdate u : updates.findByVehicleOrderByIdDesc(v)) {
      for (UpdateMedia m : u.getMedia()) media.borrar(m.getUrl());
    }
    em.createQuery("delete from UpdateMedia m where m.update.vehicle = :v").setParameter("v", v).executeUpdate();
    em.createQuery("delete from VehicleUpdate u where u.vehicle = :v").setParameter("v", v).executeUpdate();
    em.createQuery("delete from Signal s where s.vehicle = :v").setParameter("v", v).executeUpdate();
    em.createQuery("delete from PartPreorder p where p.vehicle = :v").setParameter("v", v).executeUpdate();
    em.createQuery("delete from WorkOrder o where o.vehicle = :v").setParameter("v", v).executeUpdate();
    em.createQuery("delete from ScanReading r where r.vehicle = :v").setParameter("v", v).executeUpdate();
    String placa = v.getPlate();
    Long vid = v.getId();
    em.flush();
    em.clear();
    vehicles.delete(vehicles.findById(vid).orElseThrow());
    ra.addFlashAttribute("ok", "Se eliminó la unidad " + placa + ".");
    return "redirect:/app/vehiculos";
  }

  @PostMapping("/{id}/fotos/{fotoId}/eliminar")
  @Transactional
  public String quitarFoto(@PathVariable Long id, @PathVariable Long fotoId,
                           @AuthenticationPrincipal Usuario user, RedirectAttributes ra) {
    soloSupervisor(user);
    UpdateMedia archivo = archivos.findById(fotoId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!archivo.getUpdate().getVehicle().getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    media.borrar(archivo.getUrl());
    VehicleUpdate avance = archivo.getUpdate();
    avance.getMedia().remove(archivo);
    archivos.delete(archivo);
    ra.addFlashAttribute("ok", "Foto eliminada. El zoom usa la siguiente imagen.");
    return "redirect:/app/vehiculos/" + id;
  }

  private int guardarFotos(Vehicle v, Usuario user, List<MultipartFile> files, String mensaje) throws Exception {
    if (files == null) return 0;
    VehicleUpdate u = new VehicleUpdate();
    u.setVehicle(v);
    personas.publica(u, user);
    u.setKind("ingreso");
    u.setMessage(mensaje);
    int n = 0;
    for (MultipartFile f : files) {
      if (f == null || f.isEmpty() || n >= 3) continue;
      String url = media.save(f);
      UpdateMedia m = new UpdateMedia();
      m.setUpdate(u);
      m.setUrl(url);
      m.setMediaType(MediaService.kindOf(f));
      u.getMedia().add(m);
      n++;
    }
    if (n == 0) return 0;
    updates.save(u);
    return n;
  }

  private List<String> fotosDe(Vehicle v) {
    List<String> fotos = new ArrayList<>();
    for (VehicleUpdate u : updates.findByVehicleOrderByIdDesc(v)) {
      for (UpdateMedia m : u.getMedia()) {
        if ("image".equals(m.getMediaType())) fotos.add(m.getUrl());
      }
    }
    return fotos;
  }

  private void soloSupervisor(Usuario user) {
    if (user.getRol() != Rol.ENCARGADO) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
}
