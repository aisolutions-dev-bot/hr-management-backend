package com.aisolutions.hrmanagement.repository;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.reactive.mutiny.Mutiny;

import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DropdownRepository {
  @Inject
  Mutiny.SessionFactory sessionFactory;

  // ============================================================
  // PROJECT QUERIES
  // ============================================================

  public Uni<List<DropdownOptionDTO>> findAllProjects() {
    return sessionFactory.withSession(session -> session.createQuery(
      "SELECT DISTINCT new com.aisolutions.hrmanagement.dto.DropdownOptionDTO(" +
          "p.projectCode, p.projectName) " +
          "FROM Project p " +
          "ORDER BY p.projectCode",
      DropdownOptionDTO.class)
      .getResultList())
      .onFailure().invoke(e -> {
        System.err.println("Error fetching projects: " + e.getMessage());
        e.printStackTrace();
      })
      .onFailure().recoverWithItem(e -> {
        return new ArrayList<>();
      });
  }

  public Uni<List<DropdownOptionDTO>> findOpenProjects() {
    return sessionFactory.withSession(session -> session.createQuery(
      "SELECT DISTINCT new com.aisolutions.hrmanagement.dto.DropdownOptionDTO(" +
        "p.projectCode, p.projectName) " +
        "FROM Project p " +
        "WHERE p.status = 'O' " +
        "ORDER BY p.projectCode",
      DropdownOptionDTO.class)
      .getResultList())
      .onFailure().invoke(e -> {
        System.err.println("Error fetching open projects: " + e.getMessage());
        e.printStackTrace();
      })
      .onFailure().recoverWithItem(e -> {
        return new ArrayList<>();
      });
  }

  public Uni<List<DropdownOptionDTO>> findAllCurrencies() {
    return sessionFactory.withSession(session -> session.createQuery(
        "SELECT DISTINCT new com.aisolutions.hrmanagement.dto.DropdownOptionDTO(" +
            "c.currency, c.currencyDescription) " +
            "FROM Currency c " +
            "ORDER BY c.currency",
        DropdownOptionDTO.class)
        .getResultList())
        .onFailure().invoke(e -> {
          System.err.println("Error fetching currencies: " + e.getMessage());
          e.printStackTrace();
        })
        .onFailure().recoverWithItem(e -> {
          return new ArrayList<>();
        });
  }
}
