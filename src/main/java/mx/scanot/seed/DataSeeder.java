package mx.scanot.seed;

import mx.scanot.domain.Encargado;
import mx.scanot.domain.Rol;
import mx.scanot.repo.UsuarioRepo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {
  static final String SUPERVISOR = "jh6681891@gmail.com";

  private final UsuarioRepo usuarios;
  private final HerenciaSchema herencia;
  private final PasswordEncoder encoder;

  public DataSeeder(UsuarioRepo usuarios, HerenciaSchema herencia, PasswordEncoder encoder) {
    this.usuarios = usuarios;
    this.herencia = herencia;
    this.encoder = encoder;
  }

  @Override
  public void run(String... args) {
    herencia.aplicar();
    if (!usuarios.existsByEmail(SUPERVISOR)) {
      Encargado u = new Encargado();
      u.setEmail(SUPERVISOR);
      u.setPassword(encoder.encode("Caros2026"));
      u.setDisplayName("Supervisor");
      u.setRol(Rol.ENCARGADO);
      usuarios.save(u);
    }
    herencia.aplicar();
  }
}
