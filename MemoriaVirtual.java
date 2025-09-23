// Compilar y ejecutar con:
//  javac MemoriaVirtual.java
//  java MemoriaVirtual.java gen config.txt        #Opción 1: generar referencias
//  java MemoriaVirtual.java sim NPROC NFRAMES     #Opción 2: simular ejecución

import java.io.*;
import java.util.*;

public class MemoriaVirtual {

    static class Proceso {
        final int id;
        final int tamPagina;
        final int nf, nc;
        final long[] dv;

        int punteroDV = 0;

        final Map<Long, Integer> tablaPaginas = new HashMap<>();
        final List<Integer> marcosAsignados = new ArrayList<>();

        final long totalReferencias;
        long fallosPagina = 0;
        long accesosSwap = 0;
        long aciertos = 0;

        boolean falloPrevio = false;

        Proceso(int id, int tp, int nf, int nc, long[] dv) {
            this.id = id;
            this.tamPagina = tp;
            this.nf = nf; this.nc = nc;
            this.dv = dv;
            this.totalReferencias = dv.length;
        }

        boolean terminado() { return punteroDV >= dv.length; }

        long dvActual() { return dv[punteroDV]; }

        void procesarHit() {
            if (!falloPrevio) aciertos++;
            falloPrevio = false;
            punteroDV++;
        }
    }


    static class Marco {
        int indice;
        boolean ocupado = false;
        int procesoDueno = -1;
        long paginaVirtual = -1;
        long ultimoUso = -1;
        int asignadoAProceso = -1;

        Marco(int idx) { this.indice = idx; }
    }


    static class MemoriaRAM {
        final Marco[] marcos;
        long relojLRU = 0;
        MemoriaRAM(int n) { marcos = new Marco[n]; for (int i=0;i<n;i++) marcos[i] = new Marco(i); }
    }


    static long divisionRedondeoArriba(long a, long b) { return (a + b - 1) / b; }


    static List<String> leerLineas(String path) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line; while ((line = br.readLine()) != null) { if (!line.trim().isEmpty()) out.add(line.trim()); }
        }
        return out;
    }


    // Opción 1: Generar Referencias
    static void generarReferencias(String configPath) throws Exception {
        Map<String,String> cfg = leerConfig(configPath);
        int TP = Integer.parseInt(cfg.get("TP"));
        int NPROC = Integer.parseInt(cfg.get("NPROC"));
        String[] tamanos = cfg.get("TAMS").split(",");
        if (tamanos.length != NPROC) throw new IllegalArgumentException("TAMS debe tener NPROC entradas");

        for (int pid = 0; pid < NPROC; pid++) {
            String[] partes = tamanos[pid].toLowerCase().split("x");
            int nf = Integer.parseInt(partes[0].trim());
            int nc = Integer.parseInt(partes[1].trim());

            long elementos = (long) nf * nc;
            long bytesPorMatriz = elementos * 4L;
            long baseA = 0L;
            long baseB = baseA + bytesPorMatriz;
            long baseC = baseB + bytesPorMatriz;

            List<String> lineas = new ArrayList<>((int)(elementos*3));
            for (int i = 0; i < nf; i++) {
                for (int j = 0; j < nc; j++) {
                    long desp = ((long)i * nc + j) * 4L;

                    long dvA = baseA + desp;
                    long paginaA = dvA / TP;
                    long offA = dvA % TP;
                    lineas.add("M1:[" + i + "-" + j + "]," + paginaA + "," + offA + ",r");

                    long dvB = baseB + desp;
                    long paginaB = dvB / TP;
                    long offB = dvB % TP;
                    lineas.add("M2:[" + i + "-" + j + "]," + paginaB + "," + offB + ",r");

                    long dvC = baseC + desp;
                    long paginaC = dvC / TP;
                    long offC = dvC % TP;
                    lineas.add("M3:[" + i + "-" + j + "]," + paginaC + "," + offC + ",w");
                }
            }

            long bytesTotales = baseC + bytesPorMatriz;
            long NP = divisionRedondeoArriba(bytesTotales, TP);

            String nombreSalida = "proc" + pid + ".txt";
            try (PrintWriter pw = new PrintWriter(new FileWriter(nombreSalida))) {
                pw.println("TP=" + TP);
                pw.println("NF=" + nf);
                pw.println("NC=" + nc);
                pw.println("NR=" + lineas.size());
                pw.println("NP=" + NP);
                for (String s : lineas) pw.println(s);
            }
            System.out.println("Generado " + nombreSalida + " (NR=" + lineas.size() + ", NP=" + NP + ")");
        }
    }


    static Map<String,String> leerConfig(String path) throws Exception {
        Map<String,String> m = new HashMap<>();
        for (String line : leerLineas(path)) {
            if (!line.contains("=")) continue;
            String[] kv = line.split("=", 2);
            m.put(kv[0].trim().toUpperCase(), kv[1].trim());
        }

        for (String k : Arrays.asList("TP","NPROC","TAMS")) {
            if (!m.containsKey(k)) throw new IllegalArgumentException("Falta parametro " + k);
        }
        return m;
    }


    static Proceso cargarProcesoDesdeArchivo(int pid) throws Exception {
        String path = "proc" + pid + ".txt";
        List<String> lines = leerLineas(path);
        int TP=0, NF=0, NC=0; long NR=0; long NP=0;

        System.out.println("PROC " + pid + " == Leyendo archivo de configuración ==");

        int idx = 0;
        for (; idx < lines.size(); idx++) {
            String s = lines.get(idx);
            if (!s.contains("=")) break;
            String[] kv = s.split("=",2);
            String key = kv[0].trim().toUpperCase();
            String val = kv[1].trim();
            switch (key) {
                case "TP": TP = Integer.parseInt(val); System.out.println("PROC " + pid + "leyendo TP. Tam Páginas: " + TP); break;
                case "NF": NF = Integer.parseInt(val); System.out.println("PROC " + pid + "leyendo NF. Num Filas: " + NF); break;
                case "NC": NC = Integer.parseInt(val); System.out.println("PROC " + pid + "leyendo NC. Num Cols: " + NC); break;
                case "NR": NR = Long.parseLong(val); System.out.println("PROC " + pid + "leyendo NR. Num Referencias: " + NR); break;
                case "NP": NP = Long.parseLong(val); System.out.println("PROC " + pid + "leyendo NP. Num Paginas: " + NP); break;
            }
        }

        List<Long> dv = new ArrayList<>();
        for (; idx < lines.size(); idx++) {
            String s = lines.get(idx);
            if (s.contains(",")) {
                String[] campos = s.split(",");
                long pagina = Long.parseLong(campos[1].trim());
                long offset = Long.parseLong(campos[2].trim());
                long direccion = pagina * (long)TP + offset;
                dv.add(direccion);
            } else {
                dv.add(Long.parseLong(s));
            }
        }
        if (dv.size() != NR) throw new IllegalArgumentException("NR no coincide con el número de dv en " + path);

        System.out.println("PROC " + pid + "== Terminó de leer archivo de configuración ==");

        long[] arr = new long[dv.size()];
        for (int i=0;i<dv.size();i++) arr[i] = dv.get(i);
        return new Proceso(pid, TP, NF, NC, arr);
    }


    // Opción 2: Simulación
    static void simular(int numProcesos, int marcosTotales) throws Exception {

        Proceso[] procesos = new Proceso[numProcesos];
        for (int i=0;i<numProcesos;i++) procesos[i] = cargarProcesoDesdeArchivo(i);

        int TP = procesos[0].tamPagina;
        for (int i=1;i<numProcesos;i++) if (procesos[i].tamPagina != TP) throw new IllegalStateException("Todos los procesos deben tener el mismo TP");

        if (marcosTotales % numProcesos != 0) throw new IllegalArgumentException("El número de marcos debe ser múltiplo de NPROC");

        MemoriaRAM ram = new MemoriaRAM(marcosTotales);

        int marcosPorProceso = marcosTotales / numProcesos;
        for (int pid=0, f=0; pid<numProcesos; pid++) {
            for (int k=0;k<marcosPorProceso;k++,f++) {
                ram.marcos[f].asignadoAProceso = pid;
                procesos[pid].marcosAsignados.add(f);
                System.out.println("Proceso " + pid + ": recibe marco " + f);
            }
        }

        System.out.println("Simulación:");

        Deque<Integer> cola = new ArrayDeque<>();
        for (int pid=0; pid<numProcesos; pid++) cola.add(pid);

        Set<Integer> vivos = new HashSet<>();
        for (int pid=0; pid<numProcesos; pid++) vivos.add(pid);

        while (!cola.isEmpty()) {
            int pid = cola.pollFirst();
            Proceso p = procesos[pid];
            if (p.terminado()) continue;

            System.out.println("Turno proc: " + pid);
            System.out.println("PROC " + pid + " analizando linea_: " + p.punteroDV);

            long dvActual = p.dvActual();
            long vpn = dvActual / TP;

            Integer indiceMarco = p.tablaPaginas.get(vpn);
            boolean acierto = (indiceMarco != null);

            if (acierto) {
                Marco m = ram.marcos[indiceMarco];
                m.ultimoUso = ++ram.relojLRU;
                p.procesarHit();
                System.out.println("PROC " + pid + " hits: " + p.aciertos);
                System.out.println("PROC " + pid + " envejecimiento");
            } else {
                p.fallosPagina++;
                p.falloPrevio = true;
                System.out.println("PROC " + pid + " falla de pag: " + p.fallosPagina);

                Integer indiceLibre = null;
                for (int fi : p.marcosAsignados) {
                    Marco m = ram.marcos[fi];
                    if (!m.ocupado) { indiceLibre = fi; break; }
                }
                if (indiceLibre != null) {
                    cargarPagina(ram, procesos, p, vpn, indiceLibre);
                    p.accesosSwap += 1;
                } else {
                    int victima = elegirVictimaLRU(ram, p);
                    Marco v = ram.marcos[victima];
                    if (v.procesoDueno != p.id) throw new IllegalStateException("Victima no pertenece al mismo proceso asignado");
                    procesos[p.id].tablaPaginas.remove(v.paginaVirtual);
                    cargarPagina(ram, procesos, p, vpn, victima);
                    p.accesosSwap += 2;
                }
                System.out.println("PROC " + pid + " envejecimiento");
            }

            if (!p.terminado()) {
                cola.addLast(pid);
            } else {
                System.out.println("========================");
                System.out.println("Termino proc: " + pid);
                System.out.println("========================");
                vivos.remove(pid);
                reasignarMarcosDe(ram, procesos, pid, vivos);
            }
        }


        // Respuesta
        for (Proceso p : procesos) {
            double tasaFallos = p.totalReferencias == 0 ? 0 : (p.fallosPagina * 1.0 / p.totalReferencias);
            double tasaExito  = p.totalReferencias == 0 ? 0 : (p.aciertos     * 1.0 / p.totalReferencias);
            System.out.println("Proceso: " + p.id);
            System.out.println("- Num referencias: " + p.totalReferencias);
            System.out.println("- Fallas: " + p.fallosPagina);
            System.out.println("- Hits: " + p.aciertos);
            System.out.println("- SWAP: " + p.accesosSwap);
            System.out.printf ("- Tasa fallas: %.4f%n", tasaFallos);
            System.out.printf ("- Tasa éxito: %.4f%n",  tasaExito);
        }
    }


    static void cargarPagina(MemoriaRAM ram, Proceso[] procesos, Proceso p, long vpn, int indiceMarco) {
        Marco m = ram.marcos[indiceMarco];
        m.ocupado = true;
        m.procesoDueno = p.id;
        m.paginaVirtual = vpn;
        m.ultimoUso = ++ram.relojLRU;
        p.tablaPaginas.put(vpn, indiceMarco);
    }


    static int elegirVictimaLRU(MemoriaRAM ram, Proceso p) {
        int victima = -1; long best = Long.MAX_VALUE;
        for (int fi : p.marcosAsignados) {
            Marco m = ram.marcos[fi];
            if (!m.ocupado) continue;
            if (m.ultimoUso < best) { best = m.ultimoUso; victima = fi; }
        }
        if (victima == -1) victima = p.marcosAsignados.get(0);
        return victima;
    }


    static void reasignarMarcosDe(MemoriaRAM ram, Proceso[] procesos, int pidTerminado, Set<Integer> vivos) {
        Proceso fin = procesos[pidTerminado];
        if (vivos.isEmpty()) return;

        int objetivo = -1; long maxFallos = Long.MIN_VALUE;
        for (int pid : vivos) {
            if (procesos[pid].fallosPagina > maxFallos) {
                maxFallos = procesos[pid].fallosPagina; objetivo = pid;
            }
        }
        Proceso procObjetivo = procesos[objetivo];

        for (int fi : fin.marcosAsignados) {
            Marco m = ram.marcos[fi];
            if (m.procesoDueno == pidTerminado) {
                fin.tablaPaginas.remove(m.paginaVirtual);
            }
            System.out.println("PROC " + fin.id + " removiendo marco: " + fi);
            m.ocupado = false;
            m.procesoDueno = -1;
            m.paginaVirtual = -1;
            m.asignadoAProceso = procObjetivo.id;
            procObjetivo.marcosAsignados.add(fi);
            System.out.println("PROC " + procObjetivo.id + " asignando marco nuevo " + fi);
        }
        fin.marcosAsignados.clear();
    }

    
    // Main
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Uso:\n  java MemoriaVirtual gen <config.txt>\n  java MemoriaVirtual sim <NPROC> <NFRAMES>");
            return;
        }
        switch (args[0]) {
            case "gen":
                if (args.length != 2) throw new IllegalArgumentException("gen requiere ruta a config.txt");
                generarReferencias(args[1]);
                break;
            case "sim":
                if (args.length != 3) throw new IllegalArgumentException("sim requiere NPROC y NFRAMES");
                int numProcesos = Integer.parseInt(args[1]);
                int marcosTotales = Integer.parseInt(args[2]);
                simular(numProcesos, marcosTotales);
                break;
            default:
                throw new IllegalArgumentException("Comando desconocido: " + args[0]);
        }
    }
}