package se.ostberg.pluggis.student;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Föräldravyn: elever med kod och enhet. Nås bara med föräldrarnas token. */
@RestController
@RequestMapping("/api/students")
public class StudentsController {

    public record Input(String name, String deviceId) {
    }

    public record View(Long id, String name, String deviceId, String code, boolean isDefault) {
        static View of(Student s) {
            return new View(s.getId(), s.getName(), s.getDeviceId(), s.getCode(),
                    s.getId().equals(StudentContext.defaultId()));
        }
    }

    private final StudentService students;

    public StudentsController(StudentService students) {
        this.students = students;
    }

    @GetMapping
    public List<View> list() {
        return students.all().stream().map(View::of).toList();
    }

    @PostMapping
    public View create(@RequestBody Input in) {
        return View.of(students.create(in.name(), in.deviceId()));
    }

    @PutMapping("/{id}")
    public View update(@PathVariable long id, @RequestBody Input in) {
        return View.of(students.update(id, in.name(), in.deviceId()));
    }

    /** Ny kod – den gamla slutar fungera. */
    @PostMapping("/{id}/code")
    public View newCode(@PathVariable long id) {
        return View.of(students.newCode(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        students.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
