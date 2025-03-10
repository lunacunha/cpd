import subprocess
import csv

# Definição dos executáveis e os arquivos CSV onde serão salvos os resultados
EXECUTABLES = {
    "matrixproduct.py": "matrixproduct",
    "matrixproduct2d.py": "matrixproduct2d"
}

# Tipos de multiplicação e nomes desejados nos arquivos
MULT_TYPES = {"standard": "standard_mult_py", "line": "line_mult_py"}
MATRIX_SIZES = [600, 1000, 1400, 1800, 2200, 2600, 3000]
RUNS = 2

for executable, exec_name in EXECUTABLES.items():
    for mult_name, filename_prefix in MULT_TYPES.items():
        output_filename = f"{filename_prefix}_results_{exec_name}.csv"

        # Criar arquivo CSV e adicionar cabeçalho correto
        with open(output_filename, mode='w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(["Run", "Time (s)"])  # Apenas tempo agora

        for size in MATRIX_SIZES:
            for run in range(1, RUNS + 1):
                # Executa o script Python passando "standard" ou "line" como argumento
                process = subprocess.run(
                    ["python3", executable, mult_name, str(size)],
                    capture_output=True, text=True
                )

                output_lines = process.stdout.strip().split("\n")



                # Extrai tempo de execução corretamente
                time_line = next((line for line in output_lines if "Time elapsed in seconds" in line), None)
                time_value = time_line.split(":")[1].strip() if time_line else "N/A"

                # Escreve os resultados no CSV
                with open(output_filename, mode='a', newline='') as file:
                    writer = csv.writer(file)
                    writer.writerow([run, time_value])

