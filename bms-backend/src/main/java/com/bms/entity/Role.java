package com.bms.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "roles")
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, unique = true)
    @Enumerated(EnumType.STRING)
    private RoleName name;
    
    public enum RoleName {
        ROLE_ADMIN,
        ROLE_MANAGER,
        ROLE_CASHIER
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public RoleName getName() { return name; }
    public void setName(RoleName name) { this.name = name; }
}
