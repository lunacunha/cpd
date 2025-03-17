import subprocess
import csv

EXECUTABLE = "./matrixproduct"
MULT_TYPES = {"parallel": "parallel_line_mult"} #"standard": "standard_mult_py", "line": "line_mult_py","parallel": "parallel_line_mult", "block": "block_mult"
MATRIX_SIZES = [1800] #600, 1000, 1400, 1800, 2200, 2600, 3000, 4096, 6144, 8192, 10240
BLOCK_SIZES = [256, 512]  # 128 Apenas para multiplicação por blocos
RUNS = 10

for mult_type, filename in MULT_TYPES.items():
    for size in MATRIX_SIZES:
        output_filename = f"{filename}_{size}.csv"

        with open(output_filename, mode='w', newline='') as file:
            writer = csv.writer(file)
            if mult_type == "block":
                writer.writerow(["Run", "Block Size", "Time (s)", "L1 DCM", "L2 DCM", "L3 TCM"])
            else:
                writer.writerow(["Run", "Time (s)", "L1 DCM", "L2 DCM", "L3 TCM"])

        if mult_type == "block":
            for block in BLOCK_SIZES:
                for i in range(1, RUNS + 1):
                    process = subprocess.run(
                        [EXECUTABLE, str(size), mult_type, str(block)],
                        capture_output=True, text=True
                    )

                    # Procura a linha que contenha exatamente 4 tokens (os números)
                    output_lines = process.stdout.strip().split("\n")
                    metrics_line = None
                    for line in reversed(output_lines):
                        tokens = line.strip().split()
                        if len(tokens) == 4:
                            metrics_line = tokens
                            break

                    if metrics_line:
                        time_value, l1_dcm_value, l2_dcm_value, l3_tcm_value = metrics_line
                    else:
                        time_value = l1_dcm_value = l2_dcm_value = l3_tcm_value = "N/A"

                    with open(output_filename, mode='a', newline='') as file:
                        writer = csv.writer(file)
                        writer.writerow([i, block, time_value, l1_dcm_value, l2_dcm_value, l3_tcm_value])
        else:  # Para "standard" e "line"
            for i in range(1, RUNS + 1):
                process = subprocess.run(
                    [EXECUTABLE, str(size), mult_type],
                    capture_output=True, text=True
                )

                output_lines = process.stdout.strip().split("\n")
                metrics_line = None
                for line in reversed(output_lines):
                    tokens = line.strip().split()
                    if len(tokens) == 4:
                        metrics_line = tokens
                        break

                if metrics_line:
                    time_value, l1_dcm_value, l2_dcm_value, l3_tcm_value = metrics_line
                else:
                    time_value = l1_dcm_value = l2_dcm_value = l3_tcm_value = "N/A"

                with open(output_filename, mode='a', newline='') as file:
                    writer = csv.writer(file)
                    writer.writerow([i, time_value, l1_dcm_value, l2_dcm_value, l3_tcm_value])
