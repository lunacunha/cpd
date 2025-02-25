import sys
import time

def matrixCalc(col,matrix1, matrix2):
    matrixTemp = []
    for i in range(col):
        matrix3 = []
        for j in range(col):
            temp = 0
            for k in range(col):
                temp += matrix1[i][k] * matrix2[k][j]
            matrixTemp.append(temp)
        matrix3.append(matrixTemp)
    return matrix3

def matrixLine(col,matrix1,matrix2):
    matrix3 = [[0] * col] * col
    for i in range(col):
        matrixTemp = []
        for k in range(col):
            temp = matrix1[i][k]
            for j in range(col):
                matrix3[i][j] += temp*matrix2[k][j]
        return matrix3

def main(op, col):
    if op != 1 and op != 2:
        print("Bad Input, Wrong Operation, please use 1 or 2")
        return 
    
    matrix1 = []
    matrix2 = []

    for i in range(col): 
        temp = []   
        for j in range(col):
            temp.append(float(1.0))
        matrix1.append(temp)

    for i in range(col):    
        temp = []
        for j in range(col):
            temp.append(float(i+1.0))
        matrix2.append(temp)

    start = time.time()
    if op == 1:
        matrix = matrixCalc(col,matrix1,matrix2)
    else:
        matrix = matrixLine(col,matrix1,matrix2)
    end = time.time()
    print(f"First value of matrix : {matrix[0][0]}")
    print(f"Time elapsed in seconds : {end-start}")
    
if __name__ == "__main__":
    op = int(sys.argv[1])
    col = int(sys.argv[2])
    main(op,col)