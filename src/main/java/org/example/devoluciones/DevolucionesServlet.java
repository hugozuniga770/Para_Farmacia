package org.example.devoluciones;

import com.google.gson.Gson;
import org.example.DatabaseConnection;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.*;

// Modelo para recibir los datos del HTML
class DevolucionRequest {
    String tipo;
    String codigo;
    String motivo;
}

@WebServlet("/devoluciones")
public class DevolucionesServlet extends HttpServlet {
    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        DevolucionRequest devReq = gson.fromJson(request.getReader(), DevolucionRequest.class);
        Connection conn = null;
        String nuevoCodigoDevolucion = "";

        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false); // Iniciamos transacción

            // ====> ¡CORRECCIÓN AÑADIDA AQUÍ! <====
            // Definimos el tipo de contenido y la codificación al principio del método.
            // Así, cualquier respuesta (sea de éxito o de error) usará UTF-8.
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            if ("Venta".equalsIgnoreCase(devReq.tipo)) {
                // --- LÓGICA PARA ANULAR UNA VENTA ---
                String sqlFind = "SELECT id, estado FROM ventas WHERE codigo_venta = ?";
                int ventaId = -1;
                try (PreparedStatement stmt = conn.prepareStatement(sqlFind)) {
                    stmt.setString(1, devReq.codigo);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) throw new SQLException("No se encontró la venta con el código: " + devReq.codigo);
                        if ("Anulada".equals(rs.getString("estado"))) throw new SQLException("Esta venta ya ha sido anulada.");
                        ventaId = rs.getInt("id");
                    }
                }

                String sqlGetItems = "SELECT producto_id, cantidad FROM detalle_ventas WHERE venta_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlGetItems)) {
                    stmt.setInt(1, ventaId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while(rs.next()) {
                            String sqlStock = "UPDATE productos SET stock = stock + ? WHERE id = ?";
                            try(PreparedStatement stmtStock = conn.prepareStatement(sqlStock)) {
                                stmtStock.setInt(1, rs.getInt("cantidad"));
                                stmtStock.setInt(2, rs.getInt("producto_id"));
                                stmtStock.executeUpdate();
                            }
                        }
                    }
                }

                String sqlUpdate = "UPDATE ventas SET estado = 'Anulada' WHERE id = ?";
                try(PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                    stmt.setInt(1, ventaId);
                    stmt.executeUpdate();
                }

            } else if ("Compra".equalsIgnoreCase(devReq.tipo)) {
                // --- LÓGICA PARA ANULAR UNA COMPRA ---
                String sqlFind = "SELECT id, estado FROM compras WHERE codigo_compra = ?";
                int compraId = -1;
                try (PreparedStatement stmt = conn.prepareStatement(sqlFind)) {
                    stmt.setString(1, devReq.codigo);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) throw new SQLException("No se encontró la compra con el código: " + devReq.codigo);
                        if ("Anulada".equals(rs.getString("estado"))) throw new SQLException("Esta compra ya ha sido anulada.");
                        compraId = rs.getInt("id");
                    }
                }

                String sqlGetItems = "SELECT producto_id, cantidad FROM detalle_compras WHERE compra_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sqlGetItems)) {
                    stmt.setInt(1, compraId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while(rs.next()) {
                            String sqlCheckStock = "SELECT stock FROM productos WHERE id = ?";
                            try(PreparedStatement stmtCheck = conn.prepareStatement(sqlCheckStock)) {
                                stmtCheck.setInt(1, rs.getInt("producto_id"));
                                try (ResultSet rsStock = stmtCheck.executeQuery()) {
                                    if(rsStock.next() && rsStock.getInt("stock") < rs.getInt("cantidad")) {
                                        throw new SQLException("Stock insuficiente para devolver el producto. Stock actual: " + rsStock.getInt("stock"));
                                    }
                                }
                            }
                            String sqlStock = "UPDATE productos SET stock = stock - ? WHERE id = ?";
                            try(PreparedStatement stmtStock = conn.prepareStatement(sqlStock)) {
                                stmtStock.setInt(1, rs.getInt("cantidad"));
                                stmtStock.setInt(2, rs.getInt("producto_id"));
                                stmtStock.executeUpdate();
                            }
                        }
                    }
                }

                String sqlUpdate = "UPDATE compras SET estado = 'Anulada' WHERE id = ?";
                try(PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                    stmt.setInt(1, compraId);
                    stmt.executeUpdate();
                }

            } else {
                throw new SQLException("Tipo de transacción no válido.");
            }

            // (El resto de la lógica para registrar la devolución y generar el código no cambia)
            String sqlLog = "INSERT INTO devoluciones (tipo_transaccion_original, id_transaccion_original, codigo_transaccion_original, motivo) VALUES (?, ?, ?, ?)";
            long devId;
            int transId = -1;

            // Obtenemos el ID de la transacción original de forma segura
            if ("Venta".equalsIgnoreCase(devReq.tipo)) {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM ventas WHERE codigo_venta = ?")) {
                    stmt.setString(1, devReq.codigo);
                    ResultSet rs = stmt.executeQuery();
                    if(rs.next()) transId = rs.getInt("id");
                }
            } else {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM compras WHERE codigo_compra = ?")) {
                    stmt.setString(1, devReq.codigo);
                    ResultSet rs = stmt.executeQuery();
                    if(rs.next()) transId = rs.getInt("id");
                }
            }
            if(transId == -1) throw new SQLException("No se pudo encontrar el ID de la transacción original.");

            try (PreparedStatement stmt = conn.prepareStatement(sqlLog, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, devReq.tipo);
                stmt.setInt(2, transId);
                stmt.setString(3, devReq.codigo);
                stmt.setString(4, devReq.motivo);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) devId = rs.getLong(1);
                    else throw new SQLException("No se pudo registrar la devolución.");
                }
            }

            nuevoCodigoDevolucion = "DEV-" + devId;
            String sqlUpdateCodigo = "UPDATE devoluciones SET codigo_devolucion = ? WHERE id = ?";
            try(PreparedStatement stmt = conn.prepareStatement(sqlUpdateCodigo)) {
                stmt.setString(1, nuevoCodigoDevolucion);
                stmt.setLong(2, devId);
                stmt.executeUpdate();
            }

            conn.commit();
            response.getWriter().write("{\"status\":\"success\", \"message\":\"Devolución " + nuevoCodigoDevolucion + " procesada exitosamente.\"}");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"" + e.getMessage() + "\"}");
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}