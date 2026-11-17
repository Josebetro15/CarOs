package mx.scanot.repo;

import java.util.List;
import mx.scanot.domain.Vehicle;
import mx.scanot.domain.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderRepo extends JpaRepository<WorkOrder, Long> {
  List<WorkOrder> findAllByOrderByIdDesc();
  List<WorkOrder> findByVehicleOrderByIdDesc(Vehicle vehicle);
  long countByStatus(String status);
}
