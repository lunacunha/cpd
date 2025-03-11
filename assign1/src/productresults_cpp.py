import subprocess
import csv

EXECUTABLE = "./matrixproduct"
MULT_TYPES = {"block": "block_mult"}
MATRIX_SIZES = [8192, 10240] #600, 1000, 1400, 1800, 2200, 2600, 3000, 4096, 6144
BLOCK_SIZES = [128, 256, 512]  # Apenas para multiplicação por blocos
RUNS = 10

for mult_type, filename in MULT_TYPES.items():
    for size in MATRIX_SIZES:
        output_filename = f"{filename}_{size}.csv"

        with open(output_filename, mode='w', newline='') as file:
            writer = csv.writer(file)
            if mult_type == "block":
                writer.writerow(["Run", "Block Size", "Time (s)", "L1 DCM", "L2 DCM"])
            else:
                writer.writerow(["Run", "Time (s)", "L1 DCM", "L2 DCM"])

        if mult_type == "block":
            for block in BLOCK_SIZES:
                for i in range(1, RUNS + 1):
                    process = subprocess.run(
                        [EXECUTABLE, str(size), mult_type, str(block)],
                        capture_output=True, text=True
                    )

                    output_lines = process.stdout.strip().split("\n")
                    values = output_lines[-1].split()  # Pega apenas a última linha com números

                    if len(values) == 3:
                        time_value, l1_dcm_value, l2_dcm_value = values
                    else:
                        time_value = l1_dcm_value = l2_dcm_value = "N/A"

                    with open(output_filename, mode='a', newline='') as file:
                        writer = csv.writer(file)
                        writer.writerow([i, block, time_value, l1_dcm_value, l2_dcm_value])

        else:  # Para "standard" e "line"
            for i in range(1, RUNS + 1):
                process = subprocess.run(
                    [EXECUTABLE, str(size), mult_type],
                    capture_output=True, text=True
                )

                output_lines = process.stdout.strip().split("\n")
                values = output_lines[-1].split()  # Pega apenas a última linha com números

                if len(values) == 3:
                    time_value, l1_dcm_value, l2_dcm_value = values
                else:
                    time_value = l1_dcm_value = l2_dcm_value = "N/A"

                with open(output_filename, mode='a', newline='') as file:
                    writer = csv.writer(file)
                    writer.writerow([i, time_value, l1_dcm_value, l2_dcm_value])