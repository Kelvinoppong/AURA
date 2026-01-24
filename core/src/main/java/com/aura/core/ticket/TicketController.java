package com.aura.core.ticket;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    public record CreateRequest(
            @NotBlank String subject,
            @NotBlank String body,
            String category,
            String priority,
            String customerEmail) {}

    public record UpdateStatusRequest(@NotBlank String status) {}

    @PostMapping
    public Ticket create(@RequestBody CreateRequest req) {
        return tickets.create(req.subject(), req.body(), req.category(), req.priority(), req.customerEmail());
    }

    @GetMapping
    public List<Ticket> list(@RequestParam(value = "status", required = false) String status,
                             @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        return tickets.list(status, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Ticket> get(@PathVariable("id") UUID id) {
        return tickets.byId(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/status")
    public Ticket updateStatus(@PathVariable("id") UUID id, @RequestBody UpdateStatusRequest req) {
        return tickets.updateStatus(id, req.status());
    }
}
