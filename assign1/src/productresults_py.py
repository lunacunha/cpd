import subprocess
import csv

# Definição dos executáveis e os arquivos CSV onde serão salvos os resultados
EXECUTABLES = {
    "matrixproduct.py": "matrixproduct",
    "matrixproduct2d.py": "matrixproduct2d"
}

# Tipos de multiplicação e nomes desejados nos arquivos
MULT_TYPES = {"standard": "standard_mult_py", "line": "line_mult_py"}
MATRIX_SIZES = [2600, 3000]
RUNS = 1

for executable, exec_name in EXECUTABLES.items():
    # Define a dimensão com base no nome do executável: "1d" ou "2d"
    dimension = "1d" if exec_name == "matrixproduct" else "2d"

    for mult_name, filename_prefix in MULT_TYPES.items():
        output_filename = f"{filename_prefix}results{exec_name}.csv"

        # Cria o arquivo CSV e adiciona o cabeçalho com as colunas desejadas
        with open(output_filename, mode='w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(["Run", "Dimension", "Size", "Time(s)"])

        for size in MATRIX_SIZES:
            for run in range(1, RUNS + 1):
                # Executa o script Python passando "standard" ou "line" como argumento e o tamanho da matriz
                process = subprocess.run(
                    ["python3", executable, mult_name, str(size)],
                    capture_output=True, text=True
                )

                output_lines = process.stdout.strip().split("\n")

                # Extrai o tempo de execução a partir da linha que contém "Time elapsed in seconds"
                time_line = next((line for line in output_lines if "Time elapsed in seconds" in line), None)
                time_value = time_line.split(":")[1].strip() if time_line else "N/A"

                # Escreve os resultados no CSV, incluindo run, dimensão, tamanho e tempo
                with open(output_filename, mode='a', newline='') as file:
                    writer = csv.writer(file)
                    writer.writerow([run, dimension, size, time_value])