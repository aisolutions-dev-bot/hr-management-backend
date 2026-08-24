package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.dto.DropdownOptionDTO;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@Slf4j
public class DropdownRepository {

    // ============================================================
    // PROJECT QUERIES
    // ============================================================

    public Uni<List<DropdownOptionDTO>> findAllProjects(SqlClient client) {
        return client.preparedQuery(
                "SELECT DISTINCT ProjectCode, ProjectName FROM m01Project ORDER BY ProjectCode")
            .execute()
            .map(rows -> {
                List<DropdownOptionDTO> result = new ArrayList<>();
                for (Row row : rows) {
                    result.add(new DropdownOptionDTO(
                        row.getString("ProjectCode"),
                        row.getString("ProjectName")));
                }
                return result;
            })
            .onFailure().invoke(e -> {
                System.err.println("Error fetching projects: " + e.getMessage());
                e.printStackTrace();
            })
            .onFailure().recoverWithItem(e -> new ArrayList<>());
    }

    public Uni<List<DropdownOptionDTO>> findOpenProjects(SqlClient client) {
        return client.preparedQuery(
                "SELECT DISTINCT ProjectCode, ProjectName FROM m01Project WHERE Status = 'O' ORDER BY ProjectCode")
            .execute()
            .map(rows -> {
                List<DropdownOptionDTO> result = new ArrayList<>();
                for (Row row : rows) {
                    result.add(new DropdownOptionDTO(
                        row.getString("ProjectCode"),
                        row.getString("ProjectName")));
                }
                return result;
            })
            .onFailure().invoke(e -> {
                System.err.println("Error fetching open projects: " + e.getMessage());
                e.printStackTrace();
            })
            .onFailure().recoverWithItem(e -> new ArrayList<>());
    }

    public Uni<List<DropdownOptionDTO>> findAllCurrencies(SqlClient client) {
        return client.preparedQuery(
                "SELECT DISTINCT Currency, CurrencyDesc FROM m01Currency ORDER BY Currency")
            .execute()
            .map(rows -> {
                List<DropdownOptionDTO> result = new ArrayList<>();
                for (Row row : rows) {
                    result.add(new DropdownOptionDTO(
                        row.getString("Currency"),
                        row.getString("CurrencyDesc")));
                }
                return result;
            })
            .onFailure().invoke(e -> {
                System.err.println("Error fetching currencies: " + e.getMessage());
                e.printStackTrace();
            })
            .onFailure().recoverWithItem(e -> new ArrayList<>());
    }
}
