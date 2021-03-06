// center of mass calculations

#define LSIZE_IMG 8
#define LSIZE_IMG_ALL 512



// center of mass  even better
__kernel void center_of_mass_img(__read_only image3d_t input,
							 __global float * rx,
							 __global float * ry,
							 __global float * rz,
							 __global float * rm,
							 const int Ndown){

  
  const sampler_t sampler = CLK_NORMALIZED_COORDS_FALSE |	CLK_ADDRESS_CLAMP_TO_EDGE |	CLK_FILTER_NEAREST ;

  
  int i0 = get_global_id(0);
  int j0 = get_global_id(1);
  int k0 = get_global_id(2);

  int iLoc = get_local_id(0);
  int jLoc = get_local_id(1);
  int kLoc = get_local_id(2);
  
  int iSpace = i0*Ndown;
  int jSpace = j0*Ndown;
  int kSpace = k0*Ndown;

  int iloc = iLoc+LSIZE_IMG*jLoc+LSIZE_IMG*LSIZE_IMG*kLoc;

  int iGroup = get_group_id(0);
  int jGroup = get_group_id(1);
  int kGroup = get_group_id(2);


  int iDim = get_num_groups(0);
  int jDim = get_num_groups(1);
  int kDim = get_num_groups(2);

  __local float4 parts[LSIZE_IMG_ALL];

  float val = read_imagef(input,sampler,(float4)(iSpace,jSpace,kSpace,0.f)).x;
  
  int i = i0;
  
  parts[iLoc+LSIZE_IMG*jLoc+LSIZE_IMG*LSIZE_IMG*kLoc] = val*(float4)(iSpace,jSpace,kSpace,1.f);

  barrier(CLK_LOCAL_MEM_FENCE);


  //reduce partially
  for (int n = LSIZE_IMG_ALL/2; n >0; n >>=1){
	if (iloc < n)
	  parts[iloc] += parts[iloc+n];

	
	barrier(CLK_LOCAL_MEM_FENCE);

  }

  if (iLoc+jLoc+kLoc==0){

  	rx[iGroup+jGroup*iDim+kGroup*iDim*jDim] = parts[0].x;
  	ry[iGroup+jGroup*iDim+kGroup*iDim*jDim] = parts[0].y;
  	rz[iGroup+jGroup*iDim+kGroup*iDim*jDim] = parts[0].z;
  	rm[iGroup+jGroup*iDim+kGroup*iDim*jDim] = parts[0].w;

  }
 
}

// center of mass
__kernel void center_of_mass_img_old(__read_only image3d_t input,
							 __global float * rx,
							 __global float * ry,
							 __global float * rz,
							 __global float * rm,
							 const int Ndown){

  
  const sampler_t sampler = CLK_NORMALIZED_COORDS_FALSE |	CLK_ADDRESS_CLAMP_TO_EDGE |	CLK_FILTER_NEAREST ;

  
  int i0 = get_global_id(0);
  int j0 = get_global_id(1);
  int k0 = get_global_id(2);

  int iSpace = i0*Ndown;
  int jSpace = j0*Ndown;
  int kSpace = k0*Ndown;
  
  int iLoc = get_local_id(0);
  int jLoc = get_local_id(1);
  int kLoc = get_local_id(2);

  int iGroup = get_group_id(0);
  int jGroup = get_group_id(1);
  int kGroup = get_group_id(2);

  int iDim = get_num_groups(0);
  int jDim = get_num_groups(1);
  int kDim = get_num_groups(2);

  __local float4 partialSums[LSIZE_IMG][LSIZE_IMG][LSIZE_IMG];

  float val = read_imagef(input,sampler,(float4)(iSpace,jSpace,kSpace,0.f)).x;

  int i = i0;
  
  partialSums[iLoc][jLoc][kLoc].x = val*iSpace;

  partialSums[iLoc][jLoc][kLoc].y = val*jSpace;

  partialSums[iLoc][jLoc][kLoc].z = val*kSpace;
  
  partialSums[iLoc][jLoc][kLoc].w = val;
  
  barrier(CLK_LOCAL_MEM_FENCE);
  
  //sum all up
  
  if (iLoc==0){

	float4 res = 0.f;
	for (int i2 = 0; i2 < LSIZE_IMG; ++i2)
	  for (int j2 = 0; j2 < LSIZE_IMG; ++j2)
		for (int k2 = 0; k2 < LSIZE_IMG; ++k2)
		  res += partialSums[i2][j2][k2];
	
	rx[iGroup+jGroup*iDim+kGroup*iDim*jDim] = res.x;
	ry[iGroup+jGroup*iDim+kGroup*iDim*jDim] = res.y;
	rz[iGroup+jGroup*iDim+kGroup*iDim*jDim] = res.z;
	rm[iGroup+jGroup*iDim+kGroup*iDim*jDim] = res.w;

  }
  

  
}

