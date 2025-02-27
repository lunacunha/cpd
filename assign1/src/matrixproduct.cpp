#include <stdio.h>
#include <iostream>
#include <iomanip>
#include <time.h>
#include <cstdlib>
#include <papi.h>

using namespace std;

#define SYSTEMTIME clock_t

 
void OnMult(int m_ar, int m_br) 
{
	
	SYSTEMTIME Time1, Time2;
	
	char st[100];
	double temp;
	int i, j, k;

	double *pha, *phb, *phc;
	

		
    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
	phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
	phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

	for(i=0; i<m_ar; i++)
		for(j=0; j<m_ar; j++)
			pha[i*m_ar + j] = (double)1.0;


	for(i=0; i<m_br; i++)
		for(j=0; j<m_br; j++)
			phb[i*m_br + j] = (double)(i+1);


    Time1 = clock();

	for(i=0; i<m_ar; i++)
	{	for( j=0; j<m_br; j++)
		{	temp = 0;
			for( k=0; k<m_ar; k++)
			{	
				temp += pha[i*m_ar+k] * phb[k*m_br+j];
			}
			phc[i*m_ar+j]=temp;
		}
	}


    Time2 = clock();
	sprintf(st, "Time: %3.3f seconds\n", (double)(Time2 - Time1) / CLOCKS_PER_SEC);
	cout << st;

	// display 10 elements of the result matrix tto verify correctness
	cout << "Result matrix: " << endl;
	for(i=0; i<1; i++)
	{	for(j=0; j<min(10,m_br); j++)
			cout << phc[j] << " ";
	}
	cout << endl;

    free(pha);
    free(phb);
    free(phc);
	
	
}

// add code here for line x line matriz multiplication
void OnMultLine(int m_ar, int m_br)
{
    SYSTEMTIME Time1, Time2;

    char st[100];
    double temp;
    int i, j, k;

    double *pha, *phb, *phc;

    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

    for(i=0; i<m_ar; i++)
        for(j=0; j<m_ar; j++)
            pha[i*m_ar + j] = (double)1.0;

    for(i=0; i<m_br; i++)
        for(j=0; j<m_br; j++)
            phb[i*m_br + j] = (double)(i+1);

    // we need to initialize the matrix C
    for(i=0; i<m_ar; i++)
        for(j=0; j<m_ar; j++)
            phc[i*m_ar + j] = (double)0.0;

    Time1 = clock();

    for (i = 0; i < m_ar; i++) {
        // k goes through the elements of the matrix A
        for (k = 0; k < m_ar; k++) {
            double elementA_i_k = pha[i * m_ar + k]; // element A[i,k]
            for (j = 0; j < m_br; j++) {
                // multiplies by the corresponding line in B
                phc[i * m_br + j] += elementA_i_k * phb[k * m_br + j];
            }
        }
    }

    Time2 = clock();
    sprintf(st, "Time: %3.3f seconds\n", (double)(Time2 - Time1) / CLOCKS_PER_SEC);
    cout << st;

    // display 10 elements of the result matrix tto verify correctness
    cout << "Result matrix: " << endl;
    for(i=0; i<1; i++)
    {	for(j=0; j<min(10,m_br); j++)
            cout << phc[j] << " ";
    }
    cout << endl;

    free(pha);
    free(phb);
    free(phc);
}

// add code here for block x block matriz multiplication
void OnMultBlock(int m_ar, int m_br, int bkSize)
{
    SYSTEMTIME Time1, Time2;

    char st[100];
    double temp;
    int i, j, k;
    int i_block, j_block, k_block; // to use inside de blocks

    double *pha, *phb, *phc;

    pha = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phb = (double *)malloc((m_ar * m_ar) * sizeof(double));
    phc = (double *)malloc((m_ar * m_ar) * sizeof(double));

    for(i=0; i<m_ar; i++)
        for(j=0; j<m_ar; j++)
            pha[i*m_ar + j] = (double)1.0;

    for(i=0; i<m_br; i++)
        for(j=0; j<m_br; j++)
            phb[i*m_br + j] = (double)(i+1);

    // we need to initialize the matrix C
    for(i=0; i<m_ar; i++)
        for(j=0; j<m_ar; j++)
            phc[i*m_ar + j] = (double)0.0;

    Time1 = clock();

    // logic for block algorithm
    for (i = 0; i < m_ar; i += bkSize) { // goes to the next line of blocks
        for (j = 0; j < m_ar; j += bkSize) { // goes to the next column of blocks
            for (k = 0; k < m_ar; k += bkSize) { // goes to the next block

                // to avoid invalid accesses
                int i_max = min(i + bkSize, m_ar);
                int j_max = min(j + bkSize, m_ar);
                int k_max = min(k + bkSize, m_ar);

                // inside each block - apply OnMultLine
                for (i_block = i; i_block < i_max; i_block++) {
                    for (k_block = k; k_block < k_max; k_block++) {
                        double elementA_i_k = pha[i_block * m_ar + k_block];
                        for (j_block = j; j_block < j_max; j_block++) {
                            phc[i_block * m_ar + j_block] += elementA_i_k * phb[k_block * m_ar + j_block];
                        }
                    }
                }
            }
        }
    }

    Time2 = clock();
    sprintf(st, "Time: %3.3f seconds\n", (double)(Time2 - Time1) / CLOCKS_PER_SEC);
    cout << st;

    // display 10 elements of the result matrix tto verify correctness
    cout << "Result matrix: " << endl;
    for(i=0; i<1; i++)
    {	for(j=0; j<min(10,m_br); j++)
            cout << phc[j] << " ";
    }
    cout << endl;

    free(pha);
    free(phb);
    free(phc);
    
}



void handle_error (int retval)
{
  printf("PAPI error %d: %s\n", retval, PAPI_strerror(retval));
  exit(1);
}

void init_papi() {
  int retval = PAPI_library_init(PAPI_VER_CURRENT);
  if (retval != PAPI_VER_CURRENT && retval < 0) {
    printf("PAPI library version mismatch!\n");
    exit(1);
  }
  if (retval < 0) handle_error(retval);

  std::cout << "PAPI Version Number: MAJOR: " << PAPI_VERSION_MAJOR(retval)
            << " MINOR: " << PAPI_VERSION_MINOR(retval)
            << " REVISION: " << PAPI_VERSION_REVISION(retval) << "\n";
}


int main (int argc, char *argv[]) {
	if (argc < 3) {
		cerr << "Usage: " << argv[0] << " <matrix_size> <method> [block_size]" << endl;
		return 1;
	}

	int matrix_size = atoi(argv[1]);
	string method = argv[2];
	int block_size = (argc == 4) ? atoi(argv[3]) : 128;

	int EventSet = PAPI_NULL;
	long long values[2];
	int ret;

	ret = PAPI_library_init(PAPI_VER_CURRENT);
	if (ret != PAPI_VER_CURRENT) return 1;

	ret = PAPI_create_eventset(&EventSet);
	if (ret != PAPI_OK) return 1;
	ret = PAPI_add_event(EventSet, PAPI_L1_DCM);
	if (ret != PAPI_OK) return 1;
	ret = PAPI_add_event(EventSet, PAPI_L2_DCM);
	if (ret != PAPI_OK) return 1;

	ret = PAPI_start(EventSet);
	if (ret != PAPI_OK) return 1;

	SYSTEMTIME Time1 = clock();

	if (method == "standard") {
		OnMult(matrix_size, matrix_size);
	} else if (method == "line") {
		OnMultLine(matrix_size, matrix_size);
	} else if (method == "block") {
		OnMultBlock(matrix_size, matrix_size, block_size);
	} else {
		cerr << "Invalid method! Use 'standard', 'line', or 'block'." << endl;
		return 1;
	}

	SYSTEMTIME Time2 = clock();
	double elapsedTime = (double)(Time2 - Time1) / CLOCKS_PER_SEC;

	ret = PAPI_stop(EventSet, values);
	if (ret != PAPI_OK) return 1;

	// Apenas imprime os valores necessários para os scripts Python
	cout << elapsedTime << " " << values[0] << " " << values[1] << endl;

	ret = PAPI_reset(EventSet);
	ret = PAPI_remove_event(EventSet, PAPI_L1_DCM);
	ret = PAPI_remove_event(EventSet, PAPI_L2_DCM);
	ret = PAPI_destroy_eventset(&EventSet);

	return 0;
}
